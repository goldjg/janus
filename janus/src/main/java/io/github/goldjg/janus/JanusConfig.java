package io.github.goldjg.janus;

import org.keycloak.models.RealmModel;

import java.util.Arrays;
import java.util.List;

/**
 * JANUS configuration.
 *
 * <p>Values are resolved from Keycloak realm attributes first, then from
 * environment variables, then from defaults. Realm attributes take the form
 * {@code janus.<key>} (e.g. {@code janus.tenant.id}).
 */
public class JanusConfig {

    // ─── Configuration keys ───────────────────────────────────────────────

    /** Entra tenant ID. Required. */
    public static final String KEY_TENANT_ID = "janus.tenant.id";
    public static final String ENV_TENANT_ID = "JANUS_TENANT_ID";

    /** App ID URI of the MCP gateway Entra application (e.g. api://<client-id>). Required. */
    public static final String KEY_GATEWAY_RESOURCE_URI = "janus.gateway.resource.uri";
    public static final String ENV_GATEWAY_RESOURCE_URI = "JANUS_GATEWAY_RESOURCE_URI";

    /**
     * Comma-separated list of allowed redirect URI prefixes/patterns.
     * Defaults to loopback patterns suitable for local MCP clients.
     */
    public static final String KEY_ALLOWED_REDIRECT_PATTERNS = "janus.allowed.redirect.patterns";
    public static final String ENV_ALLOWED_REDIRECT_PATTERNS = "JANUS_ALLOWED_REDIRECT_URI_PATTERNS";

    /** Maximum number of redirect URIs per registration. Defaults to 10. */
    public static final String KEY_MAX_REDIRECT_URIS = "janus.max.redirect.uris";
    public static final String ENV_MAX_REDIRECT_URIS = "JANUS_MAX_REDIRECT_URIS";

    /** Maximum length of client_name. Defaults to 64. */
    public static final String KEY_MAX_CLIENT_NAME_LENGTH = "janus.max.client.name.length";

    /** Maximum length for string fields in DCR request. Defaults to 256. */
    public static final String KEY_MAX_FIELD_LENGTH = "janus.max.field.length";

    /** Cleanup job: number of days after which a registration is considered stale. Defaults to 90. */
    public static final String KEY_CLEANUP_RETENTION_DAYS = "janus.cleanup.retention.days";
    public static final String ENV_CLEANUP_RETENTION_DAYS = "JANUS_CLEANUP_RETENTION_DAYS";

    // ─── Default values ───────────────────────────────────────────────────

    public static final List<String> DEFAULT_ALLOWED_REDIRECT_PATTERNS = List.of(
            "http://localhost:",
            "http://127.0.0.1:",
            "http://[::1]:"
    );
    public static final int DEFAULT_MAX_REDIRECT_URIS = 10;
    public static final int DEFAULT_MAX_CLIENT_NAME_LENGTH = 64;
    public static final int DEFAULT_MAX_FIELD_LENGTH = 256;
    public static final int DEFAULT_CLEANUP_RETENTION_DAYS = 90;

    // ─── Fields ───────────────────────────────────────────────────────────

    private final String tenantId;
    private final String gatewayResourceUri;
    private final List<String> allowedRedirectPatterns;
    private final int maxRedirectUris;
    private final int maxClientNameLength;
    private final int maxFieldLength;
    private final int cleanupRetentionDays;

    private JanusConfig(
            String tenantId,
            String gatewayResourceUri,
            List<String> allowedRedirectPatterns,
            int maxRedirectUris,
            int maxClientNameLength,
            int maxFieldLength,
            int cleanupRetentionDays) {
        this.tenantId = tenantId;
        this.gatewayResourceUri = gatewayResourceUri;
        this.allowedRedirectPatterns = allowedRedirectPatterns;
        this.maxRedirectUris = maxRedirectUris;
        this.maxClientNameLength = maxClientNameLength;
        this.maxFieldLength = maxFieldLength;
        this.cleanupRetentionDays = cleanupRetentionDays;
    }

    /**
     * Build a {@link JanusConfig} from realm attributes with environment variable fallback.
     *
     * @param realm the Keycloak realm; must not be null.
     */
    public static JanusConfig fromRealm(RealmModel realm) {
        String tenantId = realmAttr(realm, KEY_TENANT_ID, System.getenv(ENV_TENANT_ID));
        String gatewayResourceUri = realmAttr(realm, KEY_GATEWAY_RESOURCE_URI,
                System.getenv(ENV_GATEWAY_RESOURCE_URI));

        String patternsRaw = realmAttr(realm, KEY_ALLOWED_REDIRECT_PATTERNS,
                System.getenv(ENV_ALLOWED_REDIRECT_PATTERNS));
        List<String> patterns = patternsRaw != null
                ? Arrays.asList(patternsRaw.split(","))
                : DEFAULT_ALLOWED_REDIRECT_PATTERNS;

        int maxRedirectUris = parseInt(
                realmAttr(realm, KEY_MAX_REDIRECT_URIS, System.getenv(ENV_MAX_REDIRECT_URIS)),
                DEFAULT_MAX_REDIRECT_URIS);
        int maxClientNameLength = parseInt(
                realmAttr(realm, KEY_MAX_CLIENT_NAME_LENGTH, null),
                DEFAULT_MAX_CLIENT_NAME_LENGTH);
        int maxFieldLength = parseInt(
                realmAttr(realm, KEY_MAX_FIELD_LENGTH, null),
                DEFAULT_MAX_FIELD_LENGTH);
        int cleanupRetentionDays = parseInt(
                realmAttr(realm, KEY_CLEANUP_RETENTION_DAYS, System.getenv(ENV_CLEANUP_RETENTION_DAYS)),
                DEFAULT_CLEANUP_RETENTION_DAYS);

        return new JanusConfig(tenantId, gatewayResourceUri, patterns,
                maxRedirectUris, maxClientNameLength, maxFieldLength, cleanupRetentionDays);
    }

    /**
     * Build a {@link JanusConfig} purely from environment variables (for the cleanup job,
     * which runs outside a Keycloak session).
     */
    public static JanusConfig fromEnvironment() {
        String tenantId = System.getenv(ENV_TENANT_ID);
        String gatewayResourceUri = System.getenv(ENV_GATEWAY_RESOURCE_URI);

        String patternsRaw = System.getenv(ENV_ALLOWED_REDIRECT_PATTERNS);
        List<String> patterns = patternsRaw != null
                ? Arrays.asList(patternsRaw.split(","))
                : DEFAULT_ALLOWED_REDIRECT_PATTERNS;

        int maxRedirectUris = parseInt(System.getenv(ENV_MAX_REDIRECT_URIS), DEFAULT_MAX_REDIRECT_URIS);
        int cleanupRetentionDays = parseInt(
                System.getenv(ENV_CLEANUP_RETENTION_DAYS), DEFAULT_CLEANUP_RETENTION_DAYS);

        return new JanusConfig(tenantId, gatewayResourceUri, patterns,
                maxRedirectUris, DEFAULT_MAX_CLIENT_NAME_LENGTH,
                DEFAULT_MAX_FIELD_LENGTH, cleanupRetentionDays);
    }

    // ─── Accessors ────────────────────────────────────────────────────────

    public String getTenantId() {
        return tenantId;
    }

    public String getGatewayResourceUri() {
        return gatewayResourceUri;
    }

    public List<String> getAllowedRedirectPatterns() {
        return allowedRedirectPatterns;
    }

    public int getMaxRedirectUris() {
        return maxRedirectUris;
    }

    public int getMaxClientNameLength() {
        return maxClientNameLength;
    }

    public int getMaxFieldLength() {
        return maxFieldLength;
    }

    public int getCleanupRetentionDays() {
        return cleanupRetentionDays;
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private static String realmAttr(RealmModel realm, String key, String fallback) {
        if (realm != null) {
            String val = realm.getAttribute(key);
            if (val != null && !val.isBlank()) {
                return val;
            }
        }
        return fallback;
    }

    private static int parseInt(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
