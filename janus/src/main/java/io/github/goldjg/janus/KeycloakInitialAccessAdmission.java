package io.github.goldjg.janus;

import jakarta.ws.rs.core.HttpHeaders;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.services.clientregistration.ClientRegistrationAuth;
import org.keycloak.services.clientregistration.ClientRegistrationContext;
import org.keycloak.services.clientregistration.ClientRegistrationProvider;

import java.util.regex.Pattern;

/** Admission using Keycloak's bounded, expiring RFC 7591 initial access tokens. */
final class KeycloakInitialAccessAdmission implements RegistrationAdmission {
    private static final int MAX_AUTHORIZATION_HEADER = 8_192;
    private static final Pattern BEARER = Pattern.compile("^Bearer [A-Za-z0-9._~-]{16,8192}$",
            Pattern.CASE_INSENSITIVE);

    private final KeycloakSession session;
    private final ClientRegistrationProvider provider;
    private final ClientRegistrationAuth auth;

    KeycloakInitialAccessAdmission(KeycloakSession session, ClientRegistrationProvider provider,
            ClientRegistrationAuth auth) {
        this.session = session;
        this.provider = provider;
        this.auth = auth;
    }

    @Override
    public AdmissionTicket authorize(DcrRequest request) {
        if (auth == null) {
            throw new RegistrationAdmissionException("A valid initial access token is required");
        }
        String header = session.getContext().getRequestHeaders().getRequestHeaders()
                .getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || header.length() > MAX_AUTHORIZATION_HEADER || !BEARER.matcher(header).matches()) {
            throw new RegistrationAdmissionException("A valid initial access token is required");
        }

        ClientRepresentation representation = new ClientRepresentation();
        representation.setName(request.getClientName());
        representation.setRedirectUris(request.getRedirectUris());
        representation.setPublicClient(true);
        representation.setStandardFlowEnabled(true);
        representation.setProtocol("openid-connect");
        ClientRegistrationContext context = new ClientRegistrationContext() {
            @Override public ClientRepresentation getClient() { return representation; }
            @Override public KeycloakSession getSession() { return session; }
            @Override public ClientRegistrationProvider getProvider() { return provider; }
        };

        try {
            auth.requireCreate(context);
        } catch (RuntimeException e) {
            throw new RegistrationAdmissionException("Initial access token was rejected");
        }
        if (!auth.isInitialAccessToken() || auth.getInitialAccessModel() == null) {
            throw new RegistrationAdmissionException("An initial access token is required for DCR admission");
        }
        String subjectKey = RegistrationService.digest(header.substring("Bearer ".length()));
        RealmModel realm = session.getContext().getRealm();
        return new AdmissionTicket(subjectKey,
                () -> realm.decreaseRemainingCount(auth.getInitialAccessModel()));
    }
}
