package io.github.goldjg.janus;

import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.DefaultAzureCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * <p>Authentication uses a User-Assigned Managed Identity via
 * {@link DefaultAzureCredential}. The {@code AZURE_CLIENT_ID} environment
 * variable should be set to the client ID of the user-assigned identity.
 *
 * <p>Graph API calls use {@code java.net.http.HttpClient} directly to avoid
 * pulling the full Microsoft Graph Java SDK into the extension classpath.
 */
public class GraphClientService {

    private static final Logger log = LoggerFactory.getLogger(GraphClientService.class);

    /** Microsoft Graph base URL. */
    private static final String GRAPH_BASE = "https://graph.microsoft.com/v1.0";

    /** Graph scope for acquiring a token for the Graph API. */
    private static final String GRAPH_SCOPE = "https://graph.microsoft.com/.default";

    /** Tag applied to every JANUS-created registration. */
    public static final String TAG_JANUS_MANAGED = "janus-managed";

    /** Tag prefix that identifies the JANUS realm that created the registration. */
    public static final String TAG_REALM_PREFIX = "janus-realm:";

    /** HTTP timeout for Graph calls. */
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);

    /** Maximum retries on Graph 429 (throttling). */
    private static final int MAX_RETRIES = 3;

    /** Base back-off on throttle. */
    private static final Duration INITIAL_RETRY_DELAY = Duration.ofSeconds(1);

    private final JanusConfig config;
    private final String correlationId;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final DefaultAzureCredential credential;

    public GraphClientService(JanusConfig config, String correlationId) {
        this(config, correlationId,
                HttpClient.newBuilder()
                        .connectTimeout(HTTP_TIMEOUT)
                        .build(),
                new ObjectMapper(),
                new DefaultAzureCredentialBuilder().build());
    }

    /**
     * Test constructor that accepts collaborator overrides.
     */
    GraphClientService(
            JanusConfig config,
            String correlationId,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            DefaultAzureCredential credential) {
        this.config = config;
        this.correlationId = correlationId;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.credential = credential;
    }

    /**
     * Create an Entra application registration for an MCP client.
     *
     * @param realmName  Keycloak realm name (used in tags and display name).
     * @param request    validated DCR request.
     * @return the Entra {@code appId} (client ID) of the created registration.
     * @throws JanusRegistrationException if the Graph call fails.
     */
    public String createApplication(String realmName, DcrRequest request) {
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

        return app.appId;
    }

    /**
     * List all JANUS-managed application registrations for the given realm.
     *
     * @param realmName the Keycloak realm name.
     * @return list of JANUS-owned app registrations.
     * @throws JanusRegistrationException if the Graph call fails.
     */
    public List<GraphApplicationResponse> listJanusApplications(String realmName) {
        String tag = TAG_REALM_PREFIX + realmName;
        // Graph advanced query: filter by tag value
        String path = "/applications?$filter=tags/any(t:t+eq+'" + encodeTag(tag)
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

            return response.body();
        }
    }

    private String acquireGraphToken() {
        try {
            var tokenRequestContext = new TokenRequestContext().addScopes(GRAPH_SCOPE);
            return credential.getTokenSync(tokenRequestContext).getToken();
        } catch (Exception e) {
            throw new JanusRegistrationException(
                    "Failed to acquire Graph token [correlationId=" + correlationId + "]", e);
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
        return value.toLowerCase()
                .replaceAll("[^a-z0-9-]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "")
                .substring(0, Math.min(value.length(), maxLen));
    }

    private static String encodeTag(String tag) {
        return tag.replace("'", "''");
    }

    private static Duration parseRetryAfter(HttpResponse<?> response, Duration fallback) {
        return response.headers().firstValue("Retry-After")
                .map(v -> {
                    try {
                        return Duration.ofSeconds(Long.parseLong(v));
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
