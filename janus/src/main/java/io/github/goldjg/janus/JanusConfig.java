package io.github.goldjg.janus;

import org.keycloak.models.RealmModel;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** Immutable, fail-closed JANUS runtime configuration. */
public final class JanusConfig {
    public static final String KEY_TENANT_ID = "janus.tenant.id";
    public static final String ENV_TENANT_ID = "JANUS_TENANT_ID";
    public static final String KEY_GATEWAY_RESOURCE_URI = "janus.gateway.resource.uri";
    public static final String ENV_GATEWAY_RESOURCE_URI = "JANUS_GATEWAY_RESOURCE_URI";
    public static final String KEY_GATEWAY_CLIENT_ID = "janus.gateway.client.id";
    public static final String ENV_GATEWAY_CLIENT_ID = "JANUS_GATEWAY_CLIENT_ID";
    public static final String KEY_ALLOWED_GATEWAY_SCOPES = "janus.allowed.gateway.scopes";
    public static final String ENV_ALLOWED_GATEWAY_SCOPES = "JANUS_ALLOWED_GATEWAY_SCOPES";
    public static final String KEY_ALLOWED_REDIRECT_PATTERNS = "janus.allowed.redirect.patterns";
    public static final String ENV_ALLOWED_REDIRECT_PATTERNS = "JANUS_ALLOWED_REDIRECT_URI_PATTERNS";
    public static final String KEY_ADMISSION_MODE = "janus.admission.mode";
    public static final String ENV_ADMISSION_MODE = "JANUS_ADMISSION_MODE";
    public static final String KEY_MAX_REQUEST_BODY_BYTES = "janus.max.request.body.bytes";
    public static final String ENV_MAX_REQUEST_BODY_BYTES = "JANUS_MAX_REQUEST_BODY_BYTES";
    public static final String KEY_MAX_REDIRECT_URIS = "janus.max.redirect.uris";
    public static final String ENV_MAX_REDIRECT_URIS = "JANUS_MAX_REDIRECT_URIS";
    public static final String KEY_MAX_CLIENT_NAME_LENGTH = "janus.max.client.name.length";
    public static final String ENV_MAX_CLIENT_NAME_LENGTH = "JANUS_MAX_CLIENT_NAME_LENGTH";
    public static final String KEY_MAX_FIELD_LENGTH = "janus.max.field.length";
    public static final String ENV_MAX_FIELD_LENGTH = "JANUS_MAX_FIELD_LENGTH";
    public static final String KEY_SOURCE_RATE_PER_MINUTE = "janus.source.rate.per.minute";
    public static final String ENV_SOURCE_RATE_PER_MINUTE = "JANUS_SOURCE_RATE_PER_MINUTE";
    public static final String KEY_GLOBAL_RATE_PER_MINUTE = "janus.global.rate.per.minute";
    public static final String ENV_GLOBAL_RATE_PER_MINUTE = "JANUS_GLOBAL_RATE_PER_MINUTE";
    public static final String KEY_MAX_REGISTRATIONS_PER_PROCESS = "janus.max.registrations.per.process";
    public static final String ENV_MAX_REGISTRATIONS_PER_PROCESS = "JANUS_MAX_REGISTRATIONS_PER_PROCESS";
    public static final String KEY_IDEMPOTENCY_TTL_SECONDS = "janus.idempotency.ttl.seconds";
    public static final String ENV_IDEMPOTENCY_TTL_SECONDS = "JANUS_IDEMPOTENCY_TTL_SECONDS";
    public static final String KEY_CLEANUP_RETENTION_DAYS = "janus.cleanup.retention.days";
    public static final String ENV_CLEANUP_RETENTION_DAYS = "JANUS_CLEANUP_RETENTION_DAYS";

    public static final String ADMISSION_INITIAL_ACCESS_TOKEN = "initial-access-token";
    public static final int DEFAULT_MAX_REQUEST_BODY_BYTES = 16 * 1024;
    public static final int DEFAULT_MAX_REDIRECT_URIS = 10;
    public static final int DEFAULT_MAX_CLIENT_NAME_LENGTH = 64;
    public static final int DEFAULT_MAX_FIELD_LENGTH = 512;
    public static final int DEFAULT_SOURCE_RATE_PER_MINUTE = 5;
    public static final int DEFAULT_GLOBAL_RATE_PER_MINUTE = 25;
    public static final int DEFAULT_MAX_REGISTRATIONS_PER_PROCESS = 1_000;
    public static final int DEFAULT_IDEMPOTENCY_TTL_SECONDS = 600;
    public static final int DEFAULT_CLEANUP_RETENTION_DAYS = 30;
    public static final List<String> DEFAULT_ALLOWED_REDIRECT_PATTERNS = List.of();

    private final String tenantId;
    private final String gatewayResourceUri;
    private final String gatewayClientId;
    private final Map<String, String> allowedGatewayScopes;
    private final List<String> allowedRedirectPatterns;
    private final String admissionMode;
    private final int maxRequestBodyBytes;
    private final int maxRedirectUris;
    private final int maxClientNameLength;
    private final int maxFieldLength;
    private final int sourceRatePerMinute;
    private final int globalRatePerMinute;
    private final int maxRegistrationsPerProcess;
    private final int idempotencyTtlSeconds;
    private final int cleanupRetentionDays;

    JanusConfig(String tenantId, String gatewayResourceUri, String gatewayClientId,
            Map<String, String> allowedGatewayScopes,
            List<String> allowedRedirectPatterns, String admissionMode, int maxRequestBodyBytes,
            int maxRedirectUris, int maxClientNameLength, int maxFieldLength,
            int sourceRatePerMinute, int globalRatePerMinute, int maxRegistrationsPerProcess,
            int idempotencyTtlSeconds, int cleanupRetentionDays) {
        this.tenantId = validateTenantId(tenantId);
        this.gatewayResourceUri = validateGatewayResourceUri(gatewayResourceUri);
        this.gatewayClientId = validateUuid(gatewayClientId, "gateway client ID");
        this.allowedGatewayScopes = validateScopes(allowedGatewayScopes, this.gatewayResourceUri);
        this.allowedRedirectPatterns = validateRedirectPatterns(allowedRedirectPatterns);
        this.admissionMode = requireAdmissionMode(admissionMode);
        this.maxRequestBodyBytes = bounded("max request body bytes", maxRequestBodyBytes, 1_024, 1_048_576);
        this.maxRedirectUris = bounded("max redirect URIs", maxRedirectUris, 1, 100);
        this.maxClientNameLength = bounded("max client name length", maxClientNameLength, 1, 256);
        this.maxFieldLength = bounded("max field length", maxFieldLength, 32, 4_096);
        this.sourceRatePerMinute = bounded("source rate per minute", sourceRatePerMinute, 1, 10_000);
        this.globalRatePerMinute = bounded("global rate per minute", globalRatePerMinute, 1, 100_000);
        if (this.globalRatePerMinute < this.sourceRatePerMinute) {
            throw new IllegalArgumentException("global rate per minute must be at least the source rate");
        }
        this.maxRegistrationsPerProcess = bounded(
                "max registrations per process", maxRegistrationsPerProcess, 1, 1_000_000);
        this.idempotencyTtlSeconds = bounded("idempotency TTL seconds", idempotencyTtlSeconds, 30, 86_400);
        this.cleanupRetentionDays = bounded("cleanup retention days", cleanupRetentionDays, 1, 3_650);
    }

    public static JanusConfig fromRealm(RealmModel realm) {
        if (realm == null) {
            throw new IllegalArgumentException("Keycloak realm is required");
        }
        return fromLookup(key -> {
            String realmValue = realm.getAttribute(key.realmKey());
            return realmValue == null || realmValue.isBlank() ? System.getenv(key.envKey()) : realmValue;
        });
    }

    public static JanusConfig fromEnvironment() {
        return fromLookup(key -> System.getenv(key.envKey()));
    }

    private static JanusConfig fromLookup(Function<ConfigKey, String> lookup) {
        return new JanusConfig(
                lookup.apply(new ConfigKey(KEY_TENANT_ID, ENV_TENANT_ID)),
                lookup.apply(new ConfigKey(KEY_GATEWAY_RESOURCE_URI, ENV_GATEWAY_RESOURCE_URI)),
                lookup.apply(new ConfigKey(KEY_GATEWAY_CLIENT_ID, ENV_GATEWAY_CLIENT_ID)),
                parseScopeMappings(lookup.apply(new ConfigKey(KEY_ALLOWED_GATEWAY_SCOPES,
                        ENV_ALLOWED_GATEWAY_SCOPES))),
                List.copyOf(parseCsvRequired(lookup.apply(new ConfigKey(KEY_ALLOWED_REDIRECT_PATTERNS,
                        ENV_ALLOWED_REDIRECT_PATTERNS)), "allowed redirect URI patterns")),
                required(lookup.apply(new ConfigKey(KEY_ADMISSION_MODE, ENV_ADMISSION_MODE)), "admission mode"),
                parseInt(lookup.apply(new ConfigKey(KEY_MAX_REQUEST_BODY_BYTES, ENV_MAX_REQUEST_BODY_BYTES)),
                        DEFAULT_MAX_REQUEST_BODY_BYTES, "max request body bytes"),
                parseInt(lookup.apply(new ConfigKey(KEY_MAX_REDIRECT_URIS, ENV_MAX_REDIRECT_URIS)),
                        DEFAULT_MAX_REDIRECT_URIS, "max redirect URIs"),
                parseInt(lookup.apply(new ConfigKey(KEY_MAX_CLIENT_NAME_LENGTH, ENV_MAX_CLIENT_NAME_LENGTH)),
                        DEFAULT_MAX_CLIENT_NAME_LENGTH, "max client name length"),
                parseInt(lookup.apply(new ConfigKey(KEY_MAX_FIELD_LENGTH, ENV_MAX_FIELD_LENGTH)),
                        DEFAULT_MAX_FIELD_LENGTH, "max field length"),
                parseInt(lookup.apply(new ConfigKey(KEY_SOURCE_RATE_PER_MINUTE, ENV_SOURCE_RATE_PER_MINUTE)),
                        DEFAULT_SOURCE_RATE_PER_MINUTE, "source rate per minute"),
                parseInt(lookup.apply(new ConfigKey(KEY_GLOBAL_RATE_PER_MINUTE, ENV_GLOBAL_RATE_PER_MINUTE)),
                        DEFAULT_GLOBAL_RATE_PER_MINUTE, "global rate per minute"),
                parseInt(lookup.apply(new ConfigKey(KEY_MAX_REGISTRATIONS_PER_PROCESS,
                                ENV_MAX_REGISTRATIONS_PER_PROCESS)),
                        DEFAULT_MAX_REGISTRATIONS_PER_PROCESS, "max registrations per process"),
                parseInt(lookup.apply(new ConfigKey(KEY_IDEMPOTENCY_TTL_SECONDS, ENV_IDEMPOTENCY_TTL_SECONDS)),
                        DEFAULT_IDEMPOTENCY_TTL_SECONDS, "idempotency TTL seconds"),
                parseInt(lookup.apply(new ConfigKey(KEY_CLEANUP_RETENTION_DAYS, ENV_CLEANUP_RETENTION_DAYS)),
                        DEFAULT_CLEANUP_RETENTION_DAYS, "cleanup retention days"));
    }

    static JanusConfig forTesting() {
        String resource = "api://11111111-1111-4111-8111-111111111111";
        return new JanusConfig("22222222-2222-4222-8222-222222222222", resource,
                "11111111-1111-4111-8111-111111111111",
                Map.of(resource + "/Mcp.Access", "33333333-3333-4333-8333-333333333333"),
                List.of("http://localhost:{port}/*", "http://127.0.0.1:{port}/*",
                        "http://[::1]:{port}/*"),
                ADMISSION_INITIAL_ACCESS_TOKEN, DEFAULT_MAX_REQUEST_BODY_BYTES,
                DEFAULT_MAX_REDIRECT_URIS, DEFAULT_MAX_CLIENT_NAME_LENGTH, DEFAULT_MAX_FIELD_LENGTH,
                DEFAULT_SOURCE_RATE_PER_MINUTE, DEFAULT_GLOBAL_RATE_PER_MINUTE,
                DEFAULT_MAX_REGISTRATIONS_PER_PROCESS, DEFAULT_IDEMPOTENCY_TTL_SECONDS,
                DEFAULT_CLEANUP_RETENTION_DAYS);
    }

    public String getTenantId() { return tenantId; }
    public String getGatewayResourceUri() { return gatewayResourceUri; }
    public String getGatewayClientId() { return gatewayClientId; }
    public Set<String> getAllowedGatewayScopes() { return allowedGatewayScopes.keySet(); }
    public Map<String, String> getGatewayScopePermissionIds() { return allowedGatewayScopes; }
    public List<String> getAllowedRedirectPatterns() { return allowedRedirectPatterns; }
    public String getAdmissionMode() { return admissionMode; }
    public int getMaxRequestBodyBytes() { return maxRequestBodyBytes; }
    public int getMaxRedirectUris() { return maxRedirectUris; }
    public int getMaxClientNameLength() { return maxClientNameLength; }
    public int getMaxFieldLength() { return maxFieldLength; }
    public int getSourceRatePerMinute() { return sourceRatePerMinute; }
    public int getGlobalRatePerMinute() { return globalRatePerMinute; }
    public int getMaxRegistrationsPerProcess() { return maxRegistrationsPerProcess; }
    public int getIdempotencyTtlSeconds() { return idempotencyTtlSeconds; }
    public int getCleanupRetentionDays() { return cleanupRetentionDays; }

    private static String validateTenantId(String value) {
        String tenant = required(value, "tenant ID");
        try {
            UUID parsed = UUID.fromString(tenant);
            if (!parsed.toString().equalsIgnoreCase(tenant)) {
                throw new IllegalArgumentException("tenant ID must be a canonical UUID");
            }
            return parsed.toString();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("tenant ID must be a canonical UUID", e);
        }
    }

    private static String validateGatewayResourceUri(String value) {
        String resource = required(value, "gateway resource URI");
        if (resource.length() > 512) {
            throw new IllegalArgumentException("gateway resource URI is too long");
        }
        try {
            URI uri = new URI(resource);
            String scheme = uri.getScheme();
            if (!uri.isAbsolute() || scheme == null
                    || !(scheme.equalsIgnoreCase("api") || scheme.equalsIgnoreCase("https"))
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("gateway resource URI must be an absolute api:// or https:// URI");
            }
            return resource.endsWith("/") ? resource.substring(0, resource.length() - 1) : resource;
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("gateway resource URI is malformed", e);
        }
    }

    private static Map<String, String> validateScopes(Map<String, String> scopes, String resource) {
        if (scopes == null || scopes.isEmpty()) {
            throw new IllegalArgumentException("at least one allowed gateway scope is required");
        }
        Map<String, String> checked = new java.util.LinkedHashMap<>();
        String prefix = resource + "/";
        for (Map.Entry<String, String> entry : scopes.entrySet()) {
            String scope = required(entry.getKey(), "allowed gateway scope");
            if (!scope.startsWith(prefix) || scope.length() <= prefix.length()
                    || !scope.matches("[\\x21\\x23-\\x5B\\x5D-\\x7E]+")) {
                throw new IllegalArgumentException(
                        "allowed scope must be an exact delegated scope under the configured gateway resource URI");
            }
            String permissionId = validateUuid(entry.getValue(), "gateway delegated permission ID");
            if (checked.putIfAbsent(scope, permissionId) != null) {
                throw new IllegalArgumentException("allowed gateway scopes must not contain duplicates");
            }
        }
        return Map.copyOf(checked);
    }

    private static List<String> validateRedirectPatterns(List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            throw new IllegalArgumentException("at least one allowed redirect URI pattern is required");
        }
        List<String> checked = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : patterns) {
            String pattern = required(raw, "allowed redirect URI pattern");
            RegistrationPolicy.validateConfiguredRedirectPattern(pattern);
            if (!seen.add(pattern)) {
                throw new IllegalArgumentException("allowed redirect URI patterns must not contain duplicates");
            }
            checked.add(pattern);
        }
        return List.copyOf(checked);
    }

    private static String requireAdmissionMode(String value) {
        String mode = required(value, "admission mode").toLowerCase(Locale.ROOT);
        if (!ADMISSION_INITIAL_ACCESS_TOKEN.equals(mode)) {
            throw new IllegalArgumentException(
                    "unsupported admission mode; only initial-access-token is permitted");
        }
        return mode;
    }

    private static Set<String> parseCsvRequired(String value, String label) {
        String raw = required(value, label);
        Set<String> values = new LinkedHashSet<>();
        for (String part : raw.split(",", -1)) {
            String item = part.trim();
            if (item.isEmpty()) {
                throw new IllegalArgumentException(label + " contains an empty entry");
            }
            if (!values.add(item)) {
                throw new IllegalArgumentException(label + " contains a duplicate entry");
            }
        }
        return values;
    }

    private static Map<String, String> parseScopeMappings(String value) {
        Set<String> entries = parseCsvRequired(value, "allowed gateway scopes");
        Map<String, String> mappings = new java.util.LinkedHashMap<>();
        for (String entry : entries) {
            int separator = entry.lastIndexOf('=');
            if (separator < 1 || separator == entry.length() - 1) {
                throw new IllegalArgumentException(
                        "allowed gateway scopes must use scope-uri=permission-uuid entries");
            }
            String scope = entry.substring(0, separator);
            String permissionId = entry.substring(separator + 1);
            if (mappings.putIfAbsent(scope, permissionId) != null) {
                throw new IllegalArgumentException("allowed gateway scopes contain a duplicate scope URI");
            }
        }
        return mappings;
    }

    private static String validateUuid(String value, String label) {
        String raw = required(value, label);
        try {
            UUID parsed = UUID.fromString(raw);
            if (!parsed.toString().equalsIgnoreCase(raw)) {
                throw new IllegalArgumentException(label + " must be a canonical UUID");
            }
            return parsed.toString();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(label + " must be a canonical UUID", e);
        }
    }

    private static int parseInt(String value, int defaultValue, String label) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " must be an integer", e);
        }
    }

    private static int bounded(String label, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(label + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        if (!value.equals(value.trim())) {
            throw new IllegalArgumentException(label + " must not have surrounding whitespace");
        }
        return value;
    }

    private record ConfigKey(String realmKey, String envKey) {}
}
