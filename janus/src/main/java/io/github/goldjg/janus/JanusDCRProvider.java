package io.github.goldjg.janus;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.keycloak.events.EventBuilder;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.services.clientregistration.ClientRegistrationAuth;
import org.keycloak.services.clientregistration.ClientRegistrationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Map;
import java.util.UUID;

/** RFC 7591 adapter over JANUS's protocol-neutral Entra provisioning core. */
public final class JanusDCRProvider implements ClientRegistrationProvider {
    private static final Logger log = LoggerFactory.getLogger(JanusDCRProvider.class);

    private final KeycloakSession session;
    private final InMemoryRegistrationControl control;
    private final ProvisionerFactory provisioners;
    private ClientRegistrationAuth auth;
    private EventBuilder event;

    public JanusDCRProvider(KeycloakSession session) {
        this(session, JanusDCRProviderFactory.registrationControl(), GraphClientProvisioner::new);
    }

    JanusDCRProvider(KeycloakSession session, InMemoryRegistrationControl control,
            ProvisionerFactory provisioners) {
        this.session = java.util.Objects.requireNonNull(session, "session");
        this.control = java.util.Objects.requireNonNull(control, "control");
        this.provisioners = java.util.Objects.requireNonNull(provisioners, "provisioners");
    }

    @Override
    public void setAuth(ClientRegistrationAuth auth) { this.auth = auth; }
    @Override
    public ClientRegistrationAuth getAuth() { return auth; }
    @Override
    public void setEvent(EventBuilder event) { this.event = event; }
    @Override
    public EventBuilder getEvent() { return event; }

    /** POST /realms/{realm}/clients-registrations/openid-connect. */
    public Response create(UriInfo ignored, InputStream inputStream) {
        String correlationId = UUID.randomUUID().toString();
        RealmModel realm = session.getContext().getRealm();
        if (realm == null || realm.getName() == null || realm.getName().isBlank()) {
            StructuredLog.error(log, "operation", "dcr_configuration_failure",
                    "correlationId", correlationId, "reason", "missing_realm");
            return errorResponse(503, "temporarily_unavailable",
                    "JANUS is not configured for this realm", correlationId);
        }
        String realmName = realm.getName();

        final JanusConfig config;
        try {
            config = JanusConfig.fromRealm(realm);
        } catch (IllegalArgumentException e) {
            StructuredLog.error(log, "operation", "dcr_configuration_failure",
                    "correlationId", correlationId, "realm", realmName);
            return errorResponse(503, "temporarily_unavailable",
                    "JANUS registration policy is not configured", correlationId);
        }

        final DcrRequest request;
        try {
            request = new DcrRequestParser(config).parse(inputStream);
        } catch (DcrRequestParser.DcrParseException e) {
            StructuredLog.info(log, "operation", "dcr_parse_failure",
                    "correlationId", correlationId, "realm", realmName);
            return errorResponse(400, "invalid_client_metadata", e.getMessage(), correlationId);
        }

        final RegistrationAdmission.AdmissionTicket ticket;
        try {
            ticket = new KeycloakInitialAccessAdmission(session, this, auth).authorize(request);
        } catch (RegistrationAdmissionException e) {
            StructuredLog.info(log, "operation", "dcr_admission_denied",
                    "correlationId", correlationId, "realm", realmName);
            return errorResponse(401, "invalid_token", e.getMessage(), correlationId);
        }

        RegistrationService registration = new RegistrationService(config, control,
                provisioners.create(config, correlationId));
        final RegistrationOutcome outcome;
        try {
            outcome = registration.register(realmName, ticket.subjectKey(), correlationId, request);
        } catch (RegistrationPolicyViolationException e) {
            StructuredLog.info(log, "operation", "dcr_policy_denied",
                    "correlationId", correlationId, "realm", realmName,
                    "error", e.getErrorCode());
            return errorResponse(400, e.getErrorCode(), e.getErrorDescription(), correlationId);
        } catch (RegistrationLimitException e) {
            StructuredLog.warn(log, "operation", "dcr_rate_limited",
                    "correlationId", correlationId, "realm", realmName);
            return Response.status(429).type(MediaType.APPLICATION_JSON)
                    .header("Retry-After", "60")
                    .header("X-Correlation-ID", correlationId)
                    .entity(Map.of("error", "temporarily_unavailable", "error_description", e.getMessage()))
                    .build();
        } catch (JanusRegistrationException e) {
            StructuredLog.error(log, "operation", "dcr_provisioning_failure",
                    "correlationId", correlationId, "realm", realmName);
            return errorResponse(502, "server_error",
                    "Entra application provisioning failed. Reference: " + correlationId,
                    correlationId);
        } catch (RuntimeException e) {
            StructuredLog.error(log, "operation", "dcr_internal_failure",
                    "correlationId", correlationId, "realm", realmName);
            return errorResponse(500, "server_error",
                    "Registration failed. Reference: " + correlationId, correlationId);
        }

        if (!outcome.reused()) {
            try {
                ticket.consume();
            } catch (RuntimeException e) {
                // Provisioning succeeded but admission accounting did not. Fail closed and make
                // the partial outcome visible through correlation logs; never create a second app.
                StructuredLog.error(log, "operation", "dcr_admission_accounting_failure",
                        "correlationId", correlationId, "realm", realmName,
                        "clientId", outcome.client().clientId());
                return errorResponse(500, "server_error",
                        "Registration admission accounting failed. Reference: " + correlationId,
                        correlationId);
            }
        }

        DcrResponse body = new DcrResponse(outcome.client().clientId(),
                outcome.client().displayName(), request);
        StructuredLog.info(log, "operation", "dcr_success", "correlationId", correlationId,
                "realm", realmName, "tenantId", config.getTenantId(),
                "clientId", outcome.client().clientId(), "idempotentReuse", outcome.reused());
        return Response.status(201).type(MediaType.APPLICATION_JSON)
                .header("X-Correlation-ID", correlationId).entity(body).build();
    }

    public Response get(ClientModel ignored) {
        return errorResponse(501, "not_supported", "Client read is not supported by JANUS");
    }

    public Response update(String ignored, InputStream inputStream) {
        return errorResponse(501, "not_supported", "Client update is not supported by JANUS");
    }

    public Response delete(String ignored) {
        return errorResponse(501, "not_supported", "Client deletion is not supported by JANUS");
    }

    @Override
    public void close() { }

    private static Response errorResponse(int status, String error, String description) {
        return errorResponse(status, error, description, UUID.randomUUID().toString());
    }

    private static Response errorResponse(
            int status, String error, String description, String correlationId) {
        return Response.status(status).type(MediaType.APPLICATION_JSON)
                .header("X-Correlation-ID", correlationId)
                .entity(Map.of("error", error, "error_description", description)).build();
    }

    @FunctionalInterface
    interface ProvisionerFactory {
        ClientProvisioner create(JanusConfig config, String correlationId);
    }
}
