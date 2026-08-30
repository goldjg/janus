package io.github.goldjg.janus;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RegistrationServiceTest {
    @Test
    void register_carriesTenantResourceAndOnlyApprovedScopesToProvisioning() {
        AtomicReference<ProvisioningRequest> captured = new AtomicReference<>();
        RegistrationService service = new RegistrationService(JanusConfig.forTesting(),
                new InMemoryRegistrationControl(), request -> {
                    captured.set(request);
                    return new ProvisionedClient("44444444-4444-4444-8444-444444444444", "janus-test");
                });

        RegistrationOutcome result = service.register("realm", "admission-subject", "correlation", request("Client"));

        assertFalse(result.reused());
        assertEquals("22222222-2222-4222-8222-222222222222", captured.get().tenantId());
        assertEquals("11111111-1111-4111-8111-111111111111", captured.get().gatewayClientId());
        assertEquals(List.of(scope()), captured.get().approvedGatewayScopes());
        assertEquals("33333333-3333-4333-8333-333333333333",
                captured.get().gatewayScopePermissionIds().get(scope()));
    }

    @Test
    void register_idempotentlyReusesResultWithoutSecondProvisioningCall() {
        AtomicInteger calls = new AtomicInteger();
        RegistrationService service = new RegistrationService(JanusConfig.forTesting(),
                new InMemoryRegistrationControl(), request -> {
                    calls.incrementAndGet();
                    return new ProvisionedClient("44444444-4444-4444-8444-444444444444", "janus-test");
                });

        RegistrationOutcome first = service.register("realm", "subject", "one", request("Client"));
        RegistrationOutcome second = service.register("realm", "subject", "two", request("Client"));

        assertFalse(first.reused());
        assertTrue(second.reused());
        assertEquals(first.client(), second.client());
        assertEquals(1, calls.get());
    }

    @Test
    void register_enforcesPerAdmissionSourceCreationRate() {
        JanusConfig config = configWithLimits(1, 10, 100);
        RegistrationService service = new RegistrationService(config, new InMemoryRegistrationControl(),
                request -> new ProvisionedClient("44444444-4444-4444-8444-444444444444", "janus-test"));
        service.register("realm", "same-source", "one", request("Client One"));

        assertThrows(RegistrationLimitException.class,
                () -> service.register("realm", "same-source", "two", request("Client Two")));
    }

    @Test
    void provisioningRequest_defensivelyCopiesCollections() {
        var redirects = new java.util.ArrayList<>(List.of("http://localhost:8080/callback"));
        var scopes = new java.util.ArrayList<>(List.of(scope()));
        var ids = new java.util.HashMap<>(Map.of(scope(), "33333333-3333-4333-8333-333333333333"));
        ProvisioningRequest request = new ProvisioningRequest(
                "22222222-2222-4222-8222-222222222222", RESOURCE,
                "11111111-1111-4111-8111-111111111111", "realm", "Client", redirects, scopes, ids, "c");
        redirects.clear();
        scopes.clear();
        ids.clear();
        assertEquals(1, request.redirectUris().size());
        assertEquals(1, request.approvedGatewayScopes().size());
        assertEquals(1, request.gatewayScopePermissionIds().size());
    }

    private static final String RESOURCE = "api://11111111-1111-4111-8111-111111111111";

    private static DcrRequest request(String name) {
        DcrRequest request = new DcrRequest();
        request.setClientName(name);
        request.setRedirectUris(List.of("http://localhost:8080/callback"));
        request.setScope(scope());
        return request;
    }

    private static String scope() {
        return RESOURCE + "/Mcp.Access";
    }

    private static JanusConfig configWithLimits(int source, int global, int capacity) {
        return new JanusConfig("22222222-2222-4222-8222-222222222222", RESOURCE,
                "11111111-1111-4111-8111-111111111111",
                Map.of(scope(), "33333333-3333-4333-8333-333333333333"),
                List.of("http://localhost:{port}/*"), JanusConfig.ADMISSION_INITIAL_ACCESS_TOKEN,
                JanusConfig.DEFAULT_MAX_REQUEST_BODY_BYTES, JanusConfig.DEFAULT_MAX_REDIRECT_URIS,
                JanusConfig.DEFAULT_MAX_CLIENT_NAME_LENGTH, JanusConfig.DEFAULT_MAX_FIELD_LENGTH,
                source, global, capacity, JanusConfig.DEFAULT_IDEMPOTENCY_TTL_SECONDS,
                JanusConfig.DEFAULT_CLEANUP_RETENTION_DAYS);
    }
}
