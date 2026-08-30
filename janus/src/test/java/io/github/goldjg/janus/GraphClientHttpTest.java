package io.github.goldjg.janus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraphClientHttpTest {

    private static final Instant NOW = Instant.parse("2026-08-29T12:00:00Z");
    private HttpClient httpClient;
    private List<Duration> sleeps;
    private Queue<Object> outcomes;
    private GraphClientService graph;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() throws Exception {
        httpClient = mock(HttpClient.class);
        sleeps = new ArrayList<>();
        outcomes = new ArrayDeque<>();
        doAnswer(invocation -> {
            Object outcome = outcomes.remove();
            if (outcome instanceof IOException exception) {
                throw exception;
            }
            return outcome;
        }).when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        graph = new GraphClientService("correlation", httpClient, new ObjectMapper(),
                () -> "graph-control-plane-token", sleeps::add,
                Clock.fixed(NOW, ZoneOffset.UTC), () -> 0.0d);
    }

    @Test
    void listJanusApplications_followsValidatedNextLink() throws Exception {
        outcomes.add(response(200, """
                {"value":[{"id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",\
                "appId":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"}],\
                "@odata.nextLink":"https://graph.microsoft.com/v1.0/applications?$skiptoken=next"}
                """, Map.of()));
        outcomes.add(response(200, """
                {"value":[{"id":"cccccccc-cccc-4ccc-8ccc-cccccccccccc",\
                "appId":"dddddddd-dddd-4ddd-8ddd-dddddddddddd"}]}
                """, Map.of()));

        List<GraphClientService.GraphApplicationResponse> applications =
                graph.listJanusApplications("janus");

        assertEquals(2, applications.size());
        verify(httpClient, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void listJanusApplications_rejectsCrossOriginNextLink() {
        outcomes.add(response(200, """
                {"value":[],"@odata.nextLink":"https://attacker.example/applications"}
                """, Map.of()));

        assertThrows(JanusRegistrationException.class,
                () -> graph.listJanusApplications("janus"));
    }

    @Test
    void listJanusApplications_rejectsPaginationLoop() {
        String loop = "https://graph.microsoft.com/v1.0/applications?$skiptoken=loop";
        outcomes.add(response(200, "{\"value\":[],\"@odata.nextLink\":\"" + loop + "\"}", Map.of()));
        outcomes.add(response(200, "{\"value\":[],\"@odata.nextLink\":\"" + loop + "\"}", Map.of()));

        assertThrows(JanusRegistrationException.class,
                () -> graph.listJanusApplications("janus"));
    }

    @Test
    void graphRequest_honorsRetryAfterThenSucceeds() throws Exception {
        outcomes.add(response(429, "", Map.of("Retry-After", List.of("2"))));
        outcomes.add(response(200, "{\"value\":[]}", Map.of()));

        assertTrue(graph.listJanusApplications("janus").isEmpty());
        assertEquals(List.of(Duration.ofSeconds(2)), sleeps);
    }

    @Test
    void graphRequest_retriesTransportFailureWithExponentialBase() throws Exception {
        outcomes.add(new IOException("connection reset"));
        outcomes.add(response(200, "{\"value\":[]}", Map.of()));

        assertTrue(graph.listJanusApplications("janus").isEmpty());
        assertEquals(List.of(Duration.ofSeconds(1)), sleeps);
    }

    @Test
    void graphRequest_stopsAfterFiveRetries() throws Exception {
        for (int i = 0; i < 6; i++) {
            outcomes.add(response(503, "", Map.of()));
        }

        assertThrows(JanusRegistrationException.class,
                () -> graph.listJanusApplications("janus"));
        verify(httpClient, times(6)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        assertEquals(5, sleeps.size());
    }

    @Test
    void deleteApplication_treatsNotFoundAsIdempotent() throws Exception {
        outcomes.add(response(404, "", Map.of()));

        assertFalse(graph.deleteApplication("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
    }

    @Test
    void createApplication_writesOnlyApprovedDelegatedPermissionIds() throws Exception {
        outcomes.add(response(201, """
                {"id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",\
                "appId":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"}
                """, Map.of()));
        String resource = "api://11111111-1111-4111-8111-111111111111";
        String approvedScope = resource + "/Mcp.Access";
        ProvisioningRequest request = new ProvisioningRequest(
                "22222222-2222-4222-8222-222222222222",
                resource,
                "11111111-1111-4111-8111-111111111111",
                "janus",
                "Claude Code",
                List.of("http://localhost:8080/callback"),
                List.of(approvedScope),
                Map.of(approvedScope, "33333333-3333-4333-8333-333333333333"),
                "correlation");

        graph.createApplication(request);

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        JsonNode body = new ObjectMapper().readTree(readBody(requestCaptor.getValue()));
        JsonNode resourceAccess = body.path("requiredResourceAccess").get(0);
        assertEquals("11111111-1111-4111-8111-111111111111",
                resourceAccess.path("resourceAppId").asText());
        assertEquals("33333333-3333-4333-8333-333333333333",
                resourceAccess.path("resourceAccess").get(0).path("id").asText());
        assertEquals("Scope", resourceAccess.path("resourceAccess").get(0).path("type").asText());
        assertFalse(body.has("passwordCredentials"));
        assertFalse(body.has("keyCredentials"));
        assertTrue(body.path("tags").toString().contains(GraphClientService.TAG_LIFECYCLE_SCHEMA));
    }

    @Test
    void createApplication_doesNotRetryAmbiguousTransportFailure() throws Exception {
        outcomes.add(new IOException("connection reset after send"));

        assertThrows(JanusRegistrationException.class,
                () -> graph.createApplication(provisioningRequest()));
        verify(httpClient, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        assertTrue(sleeps.isEmpty());
    }

    private static ProvisioningRequest provisioningRequest() {
        String resource = "api://11111111-1111-4111-8111-111111111111";
        String approvedScope = resource + "/Mcp.Access";
        return new ProvisioningRequest(
                "22222222-2222-4222-8222-222222222222",
                resource,
                "11111111-1111-4111-8111-111111111111",
                "janus",
                "Claude Code",
                List.of("http://localhost:8080/callback"),
                List.of(approvedScope),
                Map.of(approvedScope, "33333333-3333-4333-8333-333333333333"),
                "correlation");
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(
            int status,
            String body,
            Map<String, List<String>> headers) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(response.headers()).thenReturn(HttpHeaders.of(headers, (name, value) -> true));
        return response;
    }

    private static String readBody(HttpRequest request) {
        HttpRequest.BodyPublisher publisher = request.bodyPublisher().orElseThrow();
        var subscriber = java.net.http.HttpResponse.BodySubscribers.ofByteArray();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscriber.onSubscribe(subscription);
            }

            @Override
            public void onNext(java.nio.ByteBuffer item) {
                subscriber.onNext(List.of(item));
            }

            @Override
            public void onError(Throwable throwable) {
                subscriber.onError(throwable);
            }

            @Override
            public void onComplete() {
                subscriber.onComplete();
            }
        });
        return new String(subscriber.getBody().toCompletableFuture().join(),
                java.nio.charset.StandardCharsets.UTF_8);
    }
}
