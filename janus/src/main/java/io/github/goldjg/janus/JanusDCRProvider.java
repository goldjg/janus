package io.github.goldjg.janus;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.UUID;

/**
 * Keycloak {@link ClientRegistrationProvider} that translates RFC 7591
 * Dynamic Client Registration requests into Microsoft Entra ID application
 * registrations via Microsoft Graph.
 *
 * <p><strong>Security invariant:</strong> This provider never issues, caches,
 * exchanges, or re-signs the bearer token used to access the protected MCP
 * gateway. Microsoft Entra ID remains the issuer of the gateway access token.
 *
 * <h2>Endpoint</h2>
 * {@code POST /realms/{realm}/clients-registrations/openid-connect}
 *
 * <h2>Supported operations</h2>
 * <ul>
 *   <li>POST (create): validates the DCR request and creates an Entra app
 *       registration</li>
 *   <li>GET, PUT, DELETE: not supported; returns 501 Not Implemented</li>
 * </ul>
 */
public class JanusDCRProvider implements ClientRegistrationProvider {

    private static final Logger log = LoggerFactory.getLogger(JanusDCRProvider.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    private final KeycloakSession session;

    public JanusDCRProvider(KeycloakSession session) {
        this.session = session;
    }

    // ─── Setters required by the SPI (Keycloak calls these before create) ──

    @Override
    public void setAuth(ClientRegistrationAuth auth) {
        // JANUS uses anonymous registration (public endpoint). Initial access
        // tokens are not required but are supported if Keycloak is configured to
        // enforce them for this realm.
    }

    @Override
    public void setEvent(EventBuilder event) {
        // No Keycloak event recording needed for JANUS registrations.
    }

    @Override
    public void setKeycloakSession(KeycloakSession session) {
        // Session is provided via the constructor; this setter exists for SPI
        // compatibility with older versions of Keycloak that call it directly.
    }

    // ─── DCR endpoint ─────────────────────────────────────────────────────

    /**
     * Handle {@code POST /realms/{realm}/clients-registrations/openid-connect}.
     *
     * <p>Flow:
     * <ol>
     *   <li>Parse the RFC 7591 request body.</li>
     *   <li>Validate using {@link RegistrationPolicy}.</li>
     *   <li>Create an Entra app registration via {@link GraphClientService}.</li>
     *   <li>Return the Entra {@code appId} in the DCR response.</li>
     * </ol>
     */
    @Override
    public Response create(UriInfo uriInfo, InputStream inputStream) {
        String correlationId = UUID.randomUUID().toString();
        RealmModel realm = session.getContext().getRealm();
        String realmName = realm.getName();

        log.debug("operation=dcr_create correlationId={} realm={}", correlationId, realmName);

        // ── 1. Parse request ──────────────────────────────────────────────
        DcrRequest request;
        try {
            request = parseDcrRequest(inputStream, correlationId);
        } catch (ParseException e) {
            log.info("operation=dcr_parse_failure correlationId={} realm={} error={} description={}",
                    correlationId, realmName, e.errorCode, e.errorDescription);
            return errorResponse(400, e.errorCode, e.errorDescription);
        }

        // ── 2. Validate ───────────────────────────────────────────────────
        JanusConfig config = JanusConfig.fromRealm(realm);
        RegistrationPolicy policy = new RegistrationPolicy(config);
        try {
            policy.validate(request);
        } catch (RegistrationPolicyViolationException e) {
            log.info("operation=dcr_validation_failure correlationId={} realm={} error={} description={}",
                    correlationId, realmName, e.getErrorCode(), e.getErrorDescription());
            return errorResponse(400, e.getErrorCode(), e.getErrorDescription());
        }

        // ── 3. Create Entra app registration ──────────────────────────────
        GraphClientService graphService = new GraphClientService(config, correlationId);
        GraphClientService.CreatedApplication created;
        try {
            created = graphService.createApplication(realmName, request);
        } catch (JanusRegistrationException e) {
            log.error("operation=dcr_graph_error correlationId={} realm={} error={}",
                    correlationId, realmName, e.getMessage());
            return errorResponse(500, "server_error",
                    "An internal error occurred. Reference: " + correlationId);
        }

        // ── 4. Return DCR response ────────────────────────────────────────
        DcrResponse responseBody = new DcrResponse(created.appId(), created.displayName(), request);

        log.info("operation=dcr_success correlationId={} realm={} clientId={}",
                correlationId, realmName, created.appId());

        return Response.status(201)
                .type(MediaType.APPLICATION_JSON)
                .entity(responseBody)
                .build();
    }

    // ─── Unsupported operations ───────────────────────────────────────────

    /**
     * GET /realms/{realm}/clients-registrations/openid-connect/{clientId}
     * Not supported by JANUS.
     */
    @Override
    public Response get(ClientModel client) {
        return Response.status(501)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of(
                        "error", "not_supported",
                        "error_description", "Client read is not supported by JANUS"))
                .build();
    }

    /**
     * PUT /realms/{realm}/clients-registrations/openid-connect/{clientId}
     * Not supported by JANUS. MCP clients should submit a new DCR request.
     */
    @Override
    public Response update(String clientId, InputStream inputStream) {
        return Response.status(501)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of(
                        "error", "not_supported",
                        "error_description",
                        "Client update is not supported by JANUS. "
                                + "Submit a new registration request to obtain a new client_id."))
                .build();
    }

    /**
     * DELETE /realms/{realm}/clients-registrations/openid-connect/{clientId}
     * Not supported by JANUS. Stale registrations are cleaned up by the
     * lifecycle cleanup job.
     */
    @Override
    public Response delete(String clientId) {
        return Response.status(501)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of(
                        "error", "not_supported",
                        "error_description",
                        "Client deletion is not supported via this endpoint. "
                                + "Stale registrations are removed by the JANUS cleanup job."))
                .build();
    }

    @Override
    public void close() {
        // No resources to release.
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private DcrRequest parseDcrRequest(InputStream inputStream, String correlationId) {
        if (inputStream == null) {
            return errorBadRequest("Request body is required");
        }
        try {
            DcrRequest request = OBJECT_MAPPER.readValue(inputStream, DcrRequest.class);
            if (request == null) {
                return errorBadRequest("Request body must not be empty");
            }
            return request;
        } catch (IOException e) {
            log.info("operation=dcr_parse_failure correlationId={} error={}",
                    correlationId, e.getMessage());
            // Use a checked-exception trampoline to keep the signature clean.
            throw new ParseException("invalid_client_metadata",
                    "Request body is not valid JSON or contains unexpected fields");
        }
    }

    /**
     * Throws a {@link ParseException} to signal a 400 response for a bad request.
     * This method always throws and never returns a value.
     */
    private static <T> T errorBadRequest(String description) {
        throw new ParseException("invalid_client_metadata", description);
    }

    private static Response errorResponse(int status, String error, String description) {
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", error, "error_description", description))
                .build();
    }

    /**
     * Internal exception used to signal a parse error before the try-catch
     * boundary in {@link #create}.
     */
    private static class ParseException extends RuntimeException {
        final String errorCode;
        final String errorDescription;

        ParseException(String errorCode, String errorDescription) {
            super(errorCode + ": " + errorDescription);
            this.errorCode = errorCode;
            this.errorDescription = errorDescription;
        }
    }
}
