package io.github.goldjg.janus;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JanusConfigTest {
    private static final String RESOURCE = "api://11111111-1111-4111-8111-111111111111";

    @Test
    void constructor_acceptsStrictConfiguration() {
        JanusConfig config = config(Map.of(RESOURCE + "/Mcp.Access",
                "33333333-3333-4333-8333-333333333333"));
        assertEquals("22222222-2222-4222-8222-222222222222", config.getTenantId());
        assertEquals("11111111-1111-4111-8111-111111111111", config.getGatewayClientId());
        assertEquals(30, config.getCleanupRetentionDays());
    }

    @Test
    void constructor_rejectsMissingOrMalformedTenantBinding() {
        assertThrows(IllegalArgumentException.class, () -> config(null, validScopes()));
        assertThrows(IllegalArgumentException.class, () -> config("common", validScopes()));
    }

    @Test
    void constructor_rejectsScopeOutsideGatewayResource() {
        assertThrows(IllegalArgumentException.class,
                () -> config(Map.of("https://graph.microsoft.com/User.Read",
                        "33333333-3333-4333-8333-333333333333")));
    }

    @Test
    void constructor_rejectsMalformedPermissionUuid() {
        assertThrows(IllegalArgumentException.class,
                () -> config(Map.of(RESOURCE + "/Mcp.Access", "not-a-uuid")));
    }

    @Test
    void constructor_rejectsUnsafePrefixRedirectRule() {
        assertThrows(IllegalArgumentException.class,
                () -> config(validScopes(), List.of("https://client.example/*")));
    }

    @Test
    void constructor_rejectsDangerousRedirectSchemeEvenWhenExact() {
        assertThrows(IllegalArgumentException.class,
                () -> config(validScopes(), List.of("javascript:alert(1)")));
    }

    private static JanusConfig config(Map<String, String> scopes) {
        return config("22222222-2222-4222-8222-222222222222", scopes);
    }

    private static JanusConfig config(String tenant, Map<String, String> scopes) {
        return config(tenant, scopes, List.of("http://localhost:{port}/*"));
    }

    private static JanusConfig config(Map<String, String> scopes, List<String> redirects) {
        return config("22222222-2222-4222-8222-222222222222", scopes, redirects);
    }

    private static JanusConfig config(String tenant, Map<String, String> scopes, List<String> redirects) {
        return new JanusConfig(tenant, RESOURCE, "11111111-1111-4111-8111-111111111111", scopes,
                redirects, JanusConfig.ADMISSION_INITIAL_ACCESS_TOKEN,
                JanusConfig.DEFAULT_MAX_REQUEST_BODY_BYTES, JanusConfig.DEFAULT_MAX_REDIRECT_URIS,
                JanusConfig.DEFAULT_MAX_CLIENT_NAME_LENGTH, JanusConfig.DEFAULT_MAX_FIELD_LENGTH,
                JanusConfig.DEFAULT_SOURCE_RATE_PER_MINUTE, JanusConfig.DEFAULT_GLOBAL_RATE_PER_MINUTE,
                JanusConfig.DEFAULT_MAX_REGISTRATIONS_PER_PROCESS,
                JanusConfig.DEFAULT_IDEMPOTENCY_TTL_SECONDS, JanusConfig.DEFAULT_CLEANUP_RETENTION_DAYS);
    }

    private static Map<String, String> validScopes() {
        return Map.of(RESOURCE + "/Mcp.Access", "33333333-3333-4333-8333-333333333333");
    }
}
