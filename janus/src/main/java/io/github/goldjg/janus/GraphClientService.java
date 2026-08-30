package io.github.goldjg.janus;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Calls Microsoft Graph to create and manage JANUS-owned Entra application
 * registrations.
 *
 * <p><strong>Security invariant:</strong> This class creates and manages app
 * registrations only. It never acquires, caches, returns, or forwards a token
 * intended for the MCP gateway. Its only bearer token is a managed-identity
 * control-plane token whose audience is Microsoft Graph.
 *
 * <p>The implementation validates Graph pagination links before following
 * them, bounds response size, and retries transient Graph/transport failures
 * without logging authorization headers or response bodies.
 */
public class GraphClientService {

    private static final Logger log = LoggerFactory.getLogger(GraphClientService.class);
    private static final URI GRAPH_BASE_URI = URI.create("https://graph.microsoft.com/v1.0");
    private static final String IDENTITY_ENDPOINT_ENV = "IDENTITY_ENDPOINT";
    private static final String IDENTITY_HEADER_ENV = "IDENTITY_HEADER";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration INITIAL_RETRY_DELAY = Duration.ofSeconds(1);
    private static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(60);
    private static final int MAX_RETRIES = 5;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_PAGES = 10_000;
    private static final Pattern REALM_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern GRAPH_OBJECT_ID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** Base ownership marker on every JANUS-created registration. */
    public static final String TAG_JANUS_MANAGED = "janus-managed";
    /** Ownership schema marker required before lifecycle deletion. */
    public static final String TAG_LIFECYCLE_SCHEMA = "janus-lifecycle:v1";
    /** Realm ownership marker prefix. */
    public static final String TAG_REALM_PREFIX = "janus-realm:";
    /** Tenant binding marker prefix. */
    public static final String TAG_TENANT_PREFIX = "janus-tenant:";
    /** Per-request operational marker used to reconcile ambiguous create outcomes. */
    public static final String TAG_CORRELATION_PREFIX = "janus-correlation:";
    /** Manual exclusion marker that always prevents lifecycle deletion. */
    public static final String TAG_CLEANUP_EXCLUDED = "janus-cleanup:exclude";
    /** Trusted observer coverage marker prefix. Value is an ISO-8601 instant. */
    public static final String TAG_USE_OBSERVED_THROUGH_PREFIX = "janus-use-observed-through:";
    /** Last observed authentication/token signal marker prefix. Value is an ISO-8601 instant. */
    public static final String TAG_LAST_OBSERVED_USE_PREFIX = "janus-last-observed-use:";
    /** Explicit result from a trusted observer that no use was found. */
    public static final String TAG_NO_USE_OBSERVED = "janus-no-use-observed";

    private final String correlationId;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Supplier<String> tokenSupplier;
    private final Sleeper sleeper;
    private final Clock clock;
    private final DoubleSupplier jitterSupplier;

    /** Create a Graph client for Keycloak using managed identity authentication. */
    public GraphClientService(JanusConfig config, String correlationId) {
        this(correlationId, defaultHttpClient(), new ObjectMapper(), null,
                duration -> Thread.sleep(duration.toMillis()), Clock.systemUTC(),
                () -> ThreadLocalRandom.current().nextDouble());
        Objects.requireNonNull(config, "config");
    }

    /** Create a standalone Graph client using managed identity authentication. */
    public GraphClientService(String correlationId) {
        this(correlationId, defaultHttpClient(), new ObjectMapper(), null,
                duration -> Thread.sleep(duration.toMillis()), Clock.systemUTC(),
                () -> ThreadLocalRandom.current().nextDouble());
    }

    /** Package-private compatibility constructor for focused tests. */
    GraphClientService(
            JanusConfig config,
            String correlationId,
            HttpClient httpClient,
            ObjectMapper objectMapper) {
        this(correlationId, httpClient, objectMapper, null,
                duration -> Thread.sleep(duration.toMillis()), Clock.systemUTC(), () -> 0.0d);
        Objects.requireNonNull(config, "config");
    }

    /** Package-private constructor with all external effects injectable for tests. */
    GraphClientService(
            String correlationId,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            Supplier<String> tokenSupplier,
            Sleeper sleeper,
            Clock clock,
            DoubleSupplier jitterSupplier) {
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId is required");
        }
        this.correlationId = correlationId;
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.tokenSupplier = tokenSupplier;
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.jitterSupplier = Objects.requireNonNull(jitterSupplier, "jitterSupplier");
    }

    /**
     * Create a public client from the protocol-neutral, fully validated
     * provisioning request. Only the approved gateway delegated-scope UUIDs
     * carried in the request are written to {@code requiredResourceAccess};
     * this method performs no permission discovery, consent, or assignment.
     */
    public CreatedApplication createApplication(ProvisioningRequest request) {
        Objects.requireNonNull(request, "request");
        if (!correlationId.equals(request.correlationId())) {
            throw new IllegalArgumentException("provisioning correlationId does not match Graph client");
        }
        validateRealm(request.ownerRealm());
        validateUuid(request.tenantId(), "tenantId");
        validateUuid(request.gatewayClientId(), "gatewayClientId");

        String displayName = buildDisplayName(request.ownerRealm(), request.clientName());
        ObjectNode body = baseApplicationBody(displayName, request.redirectUris(),
                request.ownerRealm(), request.tenantId());

        ObjectNode requiredResource = objectMapper.createObjectNode();
        requiredResource.put("resourceAppId", request.gatewayClientId());
        ArrayNode resourceAccess = objectMapper.createArrayNode();
        Set<String> seenPermissionIds = new HashSet<>();
        for (String scope : request.approvedGatewayScopes()) {
            String permissionId = request.gatewayScopePermissionIds().get(scope);
            validateUuid(permissionId, "gateway delegated permission ID");
            if (!seenPermissionIds.add(permissionId)) {
                throw new IllegalArgumentException("approved scopes resolve to duplicate permission IDs");
            }
            ObjectNode access = objectMapper.createObjectNode();
            access.put("id", permissionId);
            access.put("type", "Scope");
            resourceAccess.add(access);
        }
        requiredResource.set("resourceAccess", resourceAccess);
        ArrayNode requiredResourceAccess = objectMapper.createArrayNode();
        requiredResourceAccess.add(requiredResource);
        body.set("requiredResourceAccess", requiredResourceAccess);

        return createFromBody(body, displayName, request.tenantId());
    }

    private ObjectNode baseApplicationBody(
            String displayName,
            List<String> redirectUriValues,
            String realmName,
            String tenantId) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("displayName", displayName);
        body.put("isFallbackPublicClient", true);
        body.put("signInAudience", "AzureADMyOrg");
        body.put("notes", "Created by JANUS; ownership schema v1; created " + clock.instant() + ".");
        ObjectNode publicClient = objectMapper.createObjectNode();
        ArrayNode redirectUris = objectMapper.createArrayNode();
        redirectUriValues.forEach(redirectUris::add);
        publicClient.set("redirectUris", redirectUris);
        body.set("publicClient", publicClient);
        ArrayNode tags = objectMapper.createArrayNode();
        tags.add(TAG_JANUS_MANAGED);
        tags.add(TAG_LIFECYCLE_SCHEMA);
        tags.add(realmTag(realmName));
        tags.add(TAG_TENANT_PREFIX + tenantId);
        tags.add(TAG_CORRELATION_PREFIX + correlationId);
        body.set("tags", tags);
        return body;
    }

    private CreatedApplication createFromBody(ObjectNode body, String displayName, String tenantId) {
        HttpResult response = graphRequest("POST", graphUri("/applications"), body, Set.of(201));
        GraphApplicationResponse app = parseResponse(response.body(), GraphApplicationResponse.class);
        if (!hasValidApplicationIdentifiers(app)) {
            throw new JanusRegistrationException("Graph create response omitted valid application identifiers"
                    + " [correlationId=" + correlationId + "]");
        }
        StructuredLog.info(log, "operation", "create_application", "correlationId", correlationId,
                "tenantId", tenantId, "outcome", "created",
                "clientId", app.appId, "appObjectId", app.id);
        return new CreatedApplication(app.appId, displayName);
    }

    /** Holds the Entra appId and display name returned by {@link #createApplication}. */
    public record CreatedApplication(String appId, String displayName) { }

    /**
     * List every Graph page containing applications tagged for a JANUS realm.
     * Local lifecycle policy still requires all positive ownership markers.
     */
    public List<GraphApplicationResponse> listJanusApplications(String realmName) {
        validateRealm(realmName);
        String filter = "tags/any(t:t eq '" + realmTag(realmName) + "')";
        String query = "$filter=" + urlEncode(filter)
                + "&$count=true"
                + "&$select=id,appId,displayName,notes,tags,createdDateTime";
        URI next = graphUri("/applications?" + query);
        List<GraphApplicationResponse> applications = new ArrayList<>();
        Set<URI> visited = new HashSet<>();

        for (int page = 1; next != null; page++) {
            if (page > MAX_PAGES || !visited.add(next)) {
                throw new JanusRegistrationException("Graph pagination exceeded safety bound or looped"
                        + " [correlationId=" + correlationId + "]");
            }
            HttpResult response = graphRequest("GET", validateGraphUri(next), null, Set.of(200));
            GraphListResponse parsed = parseResponse(response.body(), GraphListResponse.class);
            if (parsed.value == null) {
                throw new JanusRegistrationException("Graph list response omitted value array"
                        + " [correlationId=" + correlationId + "]");
            }
            applications.addAll(parsed.value);
            next = parsed.nextLink == null ? null : validateGraphUri(URI.create(parsed.nextLink));
        }
        return List.copyOf(applications);
    }

    /** Fetch a current application projection by immutable Graph object ID. */
    public Optional<GraphApplicationResponse> getApplication(String objectId) {
        validateObjectId(objectId);
        URI uri = graphUri("/applications/" + objectId
                + "?$select=id,appId,displayName,notes,tags,createdDateTime");
        HttpResult response = graphRequest("GET", uri, null, Set.of(200, 404));
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        return Optional.of(parseResponse(response.body(), GraphApplicationResponse.class));
    }

    /**
     * Delete an application by immutable Graph object ID after caller re-check.
     * A 404 is an idempotent already-absent result.
     *
     * @return true when Graph deleted the object; false when already absent
     */
    public boolean deleteApplication(String objectId) {
        validateObjectId(objectId);
        HttpResult response = graphRequest("DELETE", graphUri("/applications/" + objectId),
                null, Set.of(204, 404));
        boolean deleted = response.statusCode() == 204;
        StructuredLog.info(log, "operation", "delete_application", "correlationId", correlationId,
                "appObjectId", objectId, "outcome", deleted ? "deleted" : "already_absent");
        return deleted;
    }

    /** Return whether all non-name-based JANUS ownership markers match the realm. */
    public static boolean isPositivelyJanusManaged(
            GraphApplicationResponse application,
            String realmName,
            String tenantId) {
        if (application == null || realmName == null || tenantId == null
                || application.tags == null) {
            return false;
        }
        return application.tags.contains(TAG_JANUS_MANAGED)
                && application.tags.contains(TAG_LIFECYCLE_SCHEMA)
                && application.tags.contains(TAG_REALM_PREFIX + realmName)
                && application.tags.contains(TAG_TENANT_PREFIX + tenantId);
    }

    /** Return whether both Graph object ID and Entra client ID are UUIDs. */
    public static boolean hasValidApplicationIdentifiers(GraphApplicationResponse application) {
        return application != null && isUuid(application.id) && isUuid(application.appId);
    }

    private HttpResult graphRequest(
            String method,
            URI uri,
            ObjectNode body,
            Set<Integer> expectedStatuses) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", "Bearer " + graphToken())
                .header("client-request-id", correlationId);
        if ("POST".equals(method)) {
            final String serialized;
            try {
                serialized = objectMapper.writeValueAsString(body);
            } catch (IOException e) {
                throw new JanusRegistrationException("Failed to serialise Graph request body", e);
            }
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(serialized));
        } else if ("DELETE".equals(method)) {
            builder.DELETE();
        } else {
            builder.header("ConsistencyLevel", "eventual").GET();
        }
        return executeWithRetry(builder.build(), expectedStatuses, "graph", !"POST".equals(method));
    }

    private HttpResult executeWithRetry(
            HttpRequest request,
            Set<Integer> expectedStatuses,
            String operation,
            boolean retryAmbiguousFailures) {
        Duration backoff = INITIAL_RETRY_DELAY;
        for (int retry = 0; retry <= MAX_RETRIES; retry++) {
            final HttpResponse<String> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new JanusRegistrationException(operation + " HTTP call interrupted"
                        + " [correlationId=" + correlationId + "]", e);
            } catch (IOException e) {
                if (!retryAmbiguousFailures || retry == MAX_RETRIES) {
                    throw new JanusRegistrationException(operation + " HTTP call failed after "
                            + (retry + 1) + " attempts [correlationId=" + correlationId + "]", e);
                }
                Duration delay = jittered(backoff);
                StructuredLog.warn(log, "operation", operation + "_retry",
                        "correlationId", correlationId, "reason", "transport",
                        "attempt", retry + 1, "retryAfterMs", delay.toMillis());
                sleepOrFail(delay, operation);
                backoff = doubledCapped(backoff);
                continue;
            }

            String responseBody = response.body() == null ? "" : response.body();
            if (responseBody.getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES) {
                throw new JanusRegistrationException(operation + " response exceeded "
                        + MAX_RESPONSE_BYTES + " bytes [correlationId=" + correlationId + "]");
            }

            int status = response.statusCode();
            if (isRetriable(status)) {
                if (!retryAmbiguousFailures && status != 429) {
                    throw new JanusRegistrationException(operation + " returned ambiguous create status "
                            + status + "; request was not retried [correlationId="
                            + correlationId + "]");
                }
                if (retry == MAX_RETRIES) {
                    throw new JanusRegistrationException(operation + " returned " + status
                            + " after " + (retry + 1) + " attempts [correlationId="
                            + correlationId + "]");
                }
                Duration delay = retryDelay(response, backoff);
                StructuredLog.warn(log, "operation", operation + "_retry",
                        "correlationId", correlationId, "reason", "status", "status", status,
                        "attempt", retry + 1, "retryAfterMs", delay.toMillis());
                sleepOrFail(delay, operation);
                backoff = doubledCapped(backoff);
                continue;
            }
            if (!expectedStatuses.contains(status)) {
                throw new JanusRegistrationException(operation + " returned unexpected status "
                        + status + " [correlationId=" + correlationId + "]");
            }
            return new HttpResult(status, responseBody);
        }
        throw new IllegalStateException("unreachable retry state");
    }

    /** Acquire a Graph control-plane token from the Container Apps identity sidecar. */
    String acquireGraphToken() {
        String endpoint = System.getenv(IDENTITY_ENDPOINT_ENV);
        String identityHeader = System.getenv(IDENTITY_HEADER_ENV);
        String managedIdentityClientId = System.getenv("AZURE_CLIENT_ID");
        if (identityHeader == null || identityHeader.isBlank() || identityHeader.length() > 4096
                || identityHeader.indexOf('\r') >= 0 || identityHeader.indexOf('\n') >= 0) {
            throw new JanusRegistrationException(
                    "Azure Container Apps identity header is unavailable or invalid"
                            + " [correlationId=" + correlationId + "]");
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(containerAppsIdentityUri(endpoint, managedIdentityClientId))
                .timeout(HTTP_TIMEOUT)
                .header("X-IDENTITY-HEADER", identityHeader)
                .GET()
                .build();
        HttpResult response = executeWithRetry(request, Set.of(200), "managed_identity", true);
        try {
            JsonNode root = objectMapper.readTree(response.body());
            String token = root.path("access_token").asText(null);
            if (token == null || token.isBlank()) {
                throw new JanusRegistrationException("Managed identity response omitted access_token"
                        + " [correlationId=" + correlationId + "]");
            }
            return token;
        } catch (IOException e) {
            throw new JanusRegistrationException("Failed to parse managed identity token response"
                    + " [correlationId=" + correlationId + "]", e);
        }
    }

    static URI containerAppsIdentityUri(String endpoint, String clientId) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new JanusRegistrationException("IDENTITY_ENDPOINT is required in Azure Container Apps");
        }
        validateUuid(clientId, "AZURE_CLIENT_ID");
        final URI base;
        try {
            base = URI.create(endpoint.trim());
        } catch (IllegalArgumentException e) {
            throw new JanusRegistrationException("IDENTITY_ENDPOINT is malformed", e);
        }
        String host = base.getHost();
        boolean loopback = host != null && (host.equalsIgnoreCase("localhost")
                || host.equals("127.0.0.1") || host.equals("::1") || host.equals("[::1]"));
        if (!"http".equalsIgnoreCase(base.getScheme()) || !loopback
                || base.getPort() < 1 || base.getRawUserInfo() != null
                || base.getRawQuery() != null || base.getFragment() != null
                || base.getPath() == null || base.getPath().isBlank()) {
            throw new JanusRegistrationException(
                    "IDENTITY_ENDPOINT must be an unqualified local HTTP sidecar URI");
        }
        return URI.create(base + "?api-version=2019-08-01"
                + "&resource=" + urlEncode("https://graph.microsoft.com")
                + "&client_id=" + urlEncode(clientId));
    }

    private String graphToken() {
        String token = tokenSupplier == null ? acquireGraphToken() : tokenSupplier.get();
        if (token == null || token.isBlank()) {
            throw new JanusRegistrationException("Graph token provider returned no token"
                    + " [correlationId=" + correlationId + "]");
        }
        return token;
    }

    private <T> T parseResponse(String body, Class<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (IOException e) {
            throw new JanusRegistrationException("Failed to parse Graph response"
                    + " [correlationId=" + correlationId + "]", e);
        }
    }

    /** Build a bounded operational display name with a collision-avoidance suffix. */
    static String buildDisplayName(String realm, String clientName) {
        String safeRealm = sanitise(realm, 20);
        String safeName = sanitise(clientName, 30);
        String uuid = UUID.randomUUID().toString().substring(0, 8);
        return "janus-" + safeRealm + "-" + safeName + "-" + uuid;
    }

    private static String sanitise(String value, int maxLen) {
        if (value == null) {
            return "unknown";
        }
        String sanitised = value.toLowerCase()
                .replaceAll("[^a-z0-9-]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        if (sanitised.isEmpty()) {
            return "unknown";
        }
        return sanitised.substring(0, Math.min(sanitised.length(), maxLen));
    }

    private static HttpClient defaultHttpClient() {
        return HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
    }

    private static String realmTag(String realm) {
        validateRealm(realm);
        return TAG_REALM_PREFIX + realm;
    }

    private static void validateRealm(String realm) {
        if (realm == null || !REALM_PATTERN.matcher(realm).matches()) {
            throw new IllegalArgumentException("realm must match " + REALM_PATTERN.pattern());
        }
    }

    private static void validateObjectId(String objectId) {
        if (!isUuid(objectId)) {
            throw new IllegalArgumentException("objectId must be a UUID");
        }
    }

    private static void validateUuid(String value, String name) {
        if (!isUuid(value)) {
            throw new IllegalArgumentException(name + " must be a UUID");
        }
    }

    private static boolean isUuid(String value) {
        return value != null && GRAPH_OBJECT_ID_PATTERN.matcher(value).matches();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static URI graphUri(String pathAndQuery) {
        return validateGraphUri(URI.create(GRAPH_BASE_URI + pathAndQuery));
    }

    private static URI validateGraphUri(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !"graph.microsoft.com".equalsIgnoreCase(uri.getHost())
                || uri.getPort() != -1
                || uri.getRawUserInfo() != null
                || uri.getFragment() != null
                || uri.getPath() == null
                || !(uri.getPath().equals("/v1.0") || uri.getPath().startsWith("/v1.0/"))) {
            throw new JanusRegistrationException("Graph pagination URI crossed the allowed origin");
        }
        return uri;
    }

    private static boolean isRetriable(int status) {
        return status == 429 || status == 502 || status == 503 || status == 504;
    }

    private Duration retryDelay(HttpResponse<?> response, Duration fallback) {
        Optional<String> raw = response.headers().firstValue("Retry-After");
        if (raw.isEmpty()) {
            return jittered(fallback);
        }
        Duration parsed = parseRetryAfter(raw.get());
        if (parsed == null || parsed.isNegative()) {
            return jittered(fallback);
        }
        return minimum(parsed, MAX_RETRY_DELAY);
    }

    private Duration parseRetryAfter(String raw) {
        try {
            return Duration.ofSeconds(Long.parseLong(raw.trim()));
        } catch (NumberFormatException ignored) {
            try {
                Instant retryAt = ZonedDateTime.parse(raw.trim(),
                        DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return Duration.between(clock.instant(), retryAt);
            } catch (DateTimeParseException e) {
                return null;
            }
        }
    }

    private Duration jittered(Duration base) {
        double boundedJitter = Math.max(0.0d, Math.min(1.0d, jitterSupplier.getAsDouble()));
        long extraMillis = (long) (base.toMillis() * 0.25d * boundedJitter);
        return minimum(base.plusMillis(extraMillis), MAX_RETRY_DELAY);
    }

    private static Duration doubledCapped(Duration duration) {
        return minimum(duration.multipliedBy(2), MAX_RETRY_DELAY);
    }

    private static Duration minimum(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private void sleepOrFail(Duration duration, String operation) {
        try {
            sleeper.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JanusRegistrationException(operation + " retry interrupted"
                    + " [correlationId=" + correlationId + "]", e);
        }
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private record HttpResult(int statusCode, String body) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class GraphListResponse {
        @JsonProperty("value")
        public List<GraphApplicationResponse> value;
        @JsonProperty("@odata.nextLink")
        public String nextLink;
    }

    /** Minimal application projection required for lifecycle safety. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GraphApplicationResponse {
        /** Immutable Graph object ID used for re-fetch and deletion. */
        @JsonProperty("id")
        public String id;
        /** Entra application/client ID. */
        @JsonProperty("appId")
        public String appId;
        /** Operational display name; never used as an ownership decision. */
        @JsonProperty("displayName")
        public String displayName;
        /** Optional operator notes; never used as the sole ownership decision. */
        @JsonProperty("notes")
        public String notes;
        /** Ownership, exclusion, and trusted-use evidence markers. */
        @JsonProperty("tags")
        public List<String> tags;
        /** Graph creation timestamp. */
        @JsonProperty("createdDateTime")
        public String createdDateTime;
    }
}
