package io.github.goldjg.janus;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Calls Microsoft Graph to create and manage JANUS-owned Entra application
 * registrations.
 *
 * <p><strong>Security invariant:</strong> This class creates app registrations
 * only. It never acquires, caches, returns, or forwards a token intended for
 * the MCP gateway.
 *
 * <p>Authentication uses a User-Assigned Managed Identity via the Azure
 * Instance Metadata Service (IMDS). The {@code AZURE_CLIENT_ID} environment
 * variable must be set to the client ID of the user-assigned managed identity.
 *
 * <p>Uses {@code java.net.http.HttpClient} for all HTTP calls to avoid
 * introducing heavy runtime dependencies into the Keycloak extension classpath.
 */
public class GraphClientService {

    private static final Logger log = LoggerFactory.getLogger(GraphClientService.class);

    /** Microsoft Graph base URL. */
    private static final String GRAPH_BASE = "https://graph.microsoft.com/v1.0";

    /**
     * Azure IMDS token endpoint.
     * The managed identity client ID is supplied via the {@code client_id} query parameter
     * (or the {@code AZURE_CLIENT_ID} env var is used implicitly when not specified).
     */
    private static final String IMDS_TOKEN_URL =
            "http://169.254.169.254/metadata/identity/oauth2/token"
                    + "?api-version=2018-02-01"
                    + "&resource=https%3A%2F%2Fgraph.microsoft.com";

    /** Tag applied to every JANUS-created registration. */
    public static final String TAG_JANUS_MANAGED = "janus-managed";

    /** Tag prefix that identifies the JANUS realm that created the registration. */
    public static final String TAG_REALM_PREFIX = "janus-realm:";

    /** HTTP timeout for outbound calls. */
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);

    /** Maximum retries on Graph 429 (throttling) or 503. */
    private static final int MAX_RETRIES = 3;

    /** Base back-off delay. Doubled on each retry. */
    private static final Duration INITIAL_RETRY_DELAY = Duration.ofSeconds(1);

    private final JanusConfig config;
    private final String correlationId;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public GraphClientService(JanusConfig config, String correlationId) {
        this(config, correlationId,
                HttpClient.newBuilder()
                        .connectTimeout(HTTP_TIMEOUT)
                        .build(),
                new ObjectMapper());
    }

    /**
     * Package-private test constructor that accepts collaborator overrides.
     */
    GraphClientService(
            JanusConfig config,
            String correlationId,
            HttpClient httpClient,
            ObjectMapper objectMapper) {
        this.config = config;
        this.correlationId = correlationId;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Create an Entra application registration for an MCP client.
     *
     * @param realmName  Keycloak realm name (used in tags and display name).
     * @param request    validated DCR request.
     * @return the created application info (appId and displayName).
     * @throws JanusRegistrationException if the Graph call fails.
     */
    public CreatedApplication createApplication(String realmName, DcrRequest request) {
        String displayName = buildDisplayName(realmName, request.getClientName());
        String createdAt = Instant.now().toString();

        ObjectNode body = objectMapper.createObjectNode();
        body.put("displayName", displayName);
        body.put("isFallbackPublicClient", true);
        body.put("signInAudience", "AzureADMyOrg");
        body.put("notes", "Created by JANUS. Realm: " + realmName + ". Created: " + createdAt + ".");

        // Public client redirect URIs
        ObjectNode publicClient = objectMapper.createObjectNode();
        ArrayNode redirectUris = objectMapper.createArrayNode();
        if (request.getRedirectUris() != null) {
            request.getRedirectUris().forEach(redirectUris::add);
        }
        publicClient.set("redirectUris", redirectUris);
        body.set("publicClient", publicClient);

        // Ownership tags
        ArrayNode tags = objectMapper.createArrayNode();
        tags.add(TAG_JANUS_MANAGED);
        tags.add(TAG_REALM_PREFIX + realmName);
        body.set("tags", tags);

        String responseBody = graphPost("/applications", body);
        GraphApplicationResponse app = parseResponse(responseBody, GraphApplicationResponse.class);

        log.info("operation=create_application correlationId={} realm={} displayName={} appId={} objectId={}",
                correlationId, realmName, displayName, app.appId, app.id);

        return new CreatedApplication(app.appId, displayName);
    }

    /** Holds the Entra appId and display name returned by {@link #createApplication}. */
    public record CreatedApplication(String appId, String displayName) {}

    /**
     * List all JANUS-managed application registrations for the given realm.
     *
     * @param realmName the Keycloak realm name.
     * @return list of JANUS-owned app registrations.
     * @throws JanusRegistrationException if the Graph call fails.
     */
    public List<GraphApplicationResponse> listJanusApplications(String realmName) {
        String tag = TAG_REALM_PREFIX + realmName;
        String encodedTag = URLEncoder.encode(tag, StandardCharsets.UTF_8);
        String path = "/applications?$filter=tags/any(t:t%20eq%20'" + encodedTag
                + "')&$count=true&$select=id,appId,displayName,notes,tags,createdDateTime";

        String responseBody = graphGet(path);
        try {
            ObjectNode root = (ObjectNode) objectMapper.readTree(responseBody);
            var valueNode = root.get("value");
            if (valueNode == null || !valueNode.isArray()) {
                return List.of();
            }
            return objectMapper.readerForListOf(GraphApplicationResponse.class)
                    .readValue(valueNode);
        } catch (IOException e) {
            throw new JanusRegistrationException("Failed to parse Graph list response", e);
        }
    }

    /**
     * Delete an Entra application registration by its Graph object ID.
     *
     * @param objectId the Graph object ID (not the appId/client_id).
     * @throws JanusRegistrationException if the Graph call fails.
     */
    public void deleteApplication(String objectId) {
        graphDelete("/applications/" + objectId);
        log.info("operation=delete_application correlationId={} objectId={}", correlationId, objectId);
    }

    // ─── HTTP helpers ─────────────────────────────────────────────────────

    private String graphPost(String path, ObjectNode body) {
        String token = acquireGraphToken();
        String bodyStr;
        try {
            bodyStr = objectMapper.writeValueAsString(body);
        } catch (IOException e) {
            throw new JanusRegistrationException("Failed to serialise Graph request body", e);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GRAPH_BASE + path))
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("client-request-id", correlationId)
                .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
                .build();

        return executeWithRetry(request, 201);
    }

    private String graphGet(String path) {
        String token = acquireGraphToken();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GRAPH_BASE + path))
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header("ConsistencyLevel", "eventual")
                .header("client-request-id", correlationId)
                .GET()
                .build();

        return executeWithRetry(request, 200);
    }

    private void graphDelete(String path) {
        String token = acquireGraphToken();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GRAPH_BASE + path))
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header("client-request-id", correlationId)
                .DELETE()
                .build();

        executeWithRetry(request, 204);
    }

    private String executeWithRetry(HttpRequest request, int expectedStatus) {
        int attempt = 0;
        Duration delay = INITIAL_RETRY_DELAY;

        while (true) {
            attempt++;
            HttpResponse<String> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new JanusRegistrationException(
                        "Graph HTTP call failed [correlationId=" + correlationId + "]", e);
            }

            int status = response.statusCode();

            if (status == 429 || status == 503) {
                if (attempt >= MAX_RETRIES) {
                    throw new JanusRegistrationException(
                            "Graph returned " + status + " after " + attempt
                                    + " attempts [correlationId=" + correlationId + "]");
                }
                Duration retryAfter = parseRetryAfter(response, delay);
                log.warn("operation=graph_throttle correlationId={} status={} attempt={} retryAfterMs={}",
                        correlationId, status, attempt, retryAfter.toMillis());
                sleep(retryAfter);
                delay = delay.multipliedBy(2); // exponential back-off
                continue;
            }

            if (status != expectedStatus) {
                throw new JanusRegistrationException(
                        "Graph returned unexpected status " + status
                                + " (expected " + expectedStatus + ") "
                                + "[correlationId=" + correlationId + "]");
            }

            return response.body() != null ? response.body() : "";
        }
    }

    /**
     * Acquire a Microsoft Graph access token using the Azure IMDS endpoint.
     *
     * <p>The managed identity client ID is read from the {@code AZURE_CLIENT_ID}
     * environment variable. When running on Azure Container Apps with a
     * user-assigned managed identity, this environment variable must be set.
     *
     * <p>The token is NOT cached or stored. A new token is acquired for each
     * top-level operation. Azure IMDS handles token caching internally.
     */
    String acquireGraphToken() {
        String url = IMDS_TOKEN_URL;
        String managedIdentityClientId = System.getenv("AZURE_CLIENT_ID");
        if (managedIdentityClientId != null && !managedIdentityClientId.isBlank()) {
            url += "&client_id=" + URLEncoder.encode(managedIdentityClientId, StandardCharsets.UTF_8);
        }

        HttpRequest imdsRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .header("Metadata", "true")
                .GET()
                .build();

        HttpResponse<String> imdsResponse;
        try {
            imdsResponse = httpClient.send(imdsRequest, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new JanusRegistrationException(
                    "Failed to reach IMDS endpoint [correlationId=" + correlationId + "]", e);
        }

        if (imdsResponse.statusCode() != 200) {
            throw new JanusRegistrationException(
                    "IMDS returned status " + imdsResponse.statusCode()
                            + " [correlationId=" + correlationId + "]");
        }

        try {
            ObjectNode tokenResponse = (ObjectNode) objectMapper.readTree(imdsResponse.body());
            String token = tokenResponse.path("access_token").asText(null);
            if (token == null || token.isBlank()) {
                throw new JanusRegistrationException(
                        "IMDS response did not contain access_token [correlationId=" + correlationId + "]");
            }
            return token;
        } catch (IOException e) {
            throw new JanusRegistrationException(
                    "Failed to parse IMDS token response [correlationId=" + correlationId + "]", e);
        }
    }

    private <T> T parseResponse(String body, Class<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (IOException e) {
            throw new JanusRegistrationException("Failed to parse Graph response", e);
        }
    }

    // ─── Utilities ────────────────────────────────────────────────────────

    /**
     * Build a deterministic display name for the Entra app registration.
     *
     * <p>Format: {@code janus-<realm>-<sanitised-name>-<uuid-prefix>}
     */
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
        return sanitised.substring(0, Math.min(sanitised.length(), maxLen));
    }

    private static Duration parseRetryAfter(HttpResponse<?> response, Duration fallback) {
        return response.headers().firstValue("Retry-After")
                .map(v -> {
                    try {
                        return Duration.ofSeconds(Long.parseLong(v.trim()));
                    } catch (NumberFormatException e) {
                        return fallback;
                    }
                })
                .orElse(fallback);
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ─── Inner response model ─────────────────────────────────────────────

    /** Minimal representation of a Graph application object. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GraphApplicationResponse {

        /** Graph object ID (used for DELETE). */
        @JsonProperty("id")
        public String id;

        /** Entra application (client) ID returned to the DCR caller. */
        @JsonProperty("appId")
        public String appId;

        @JsonProperty("displayName")
        public String displayName;

        @JsonProperty("notes")
        public String notes;

        @JsonProperty("tags")
        public List<String> tags;

        @JsonProperty("createdDateTime")
        public String createdDateTime;
    }
}
