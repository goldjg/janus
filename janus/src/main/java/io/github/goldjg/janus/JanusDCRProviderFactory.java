package io.github.goldjg.janus;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.services.clientregistration.ClientRegistrationProvider;
import org.keycloak.services.clientregistration.ClientRegistrationProviderFactory;

/**
 * Keycloak SPI factory for {@link JanusDCRProvider}.
 *
 * <p>This factory registers with the ID {@code "openid-connect"} and an
 * {@link #order()} of {@code 100}, which causes Keycloak to prefer this
 * factory over the built-in {@code openid-connect} provider when both are on
 * the classpath. The JANUS realm is configured exclusively for JANUS
 * registration, so the built-in Keycloak DCR provider is not needed.
 *
 * <p>Registered via
 * {@code META-INF/services/org.keycloak.services.clientregistration.ClientRegistrationProviderFactory}.
 */
public class JanusDCRProviderFactory implements ClientRegistrationProviderFactory {

    /**
     * SPI provider ID.
     *
     * <p>Using {@code "openid-connect"} makes JANUS available at the standard
     * RFC 7591 / OpenID Connect Dynamic Client Registration endpoint:
     * {@code /realms/{realm}/clients-registrations/openid-connect}.
     */
    public static final String PROVIDER_ID = "openid-connect";

    @Override
    public ClientRegistrationProvider create(KeycloakSession session) {
        return new JanusDCRProvider(session);
    }

    @Override
    public void init(Config.Scope config) {
        // No global initialisation required.
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // No post-initialisation required.
    }

    @Override
    public void close() {
        // No resources to release.
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    /**
     * Higher order causes Keycloak to prefer this factory over the built-in
     * {@code openid-connect} provider (which has order 0).
     */
    @Override
    public int order() {
        return 100;
    }
}
