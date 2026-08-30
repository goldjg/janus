package io.github.goldjg.janus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ReferenceGatewayClaimsPolicyTest {
    private static final long NOW = 2_000_000_000L;
    private ReferenceGatewayClaimsPolicy users;

    @BeforeEach
    void setUp() {
        users = policy(ReferenceGatewayClaimsPolicy.SubjectType.USER);
    }

    @Test
    void acceptsValidUserTokenClaimsAndConfiguredClockSkew() {
        Map<String, Object> claims = userClaims();
        claims.put("iat", NOW + 59);
        claims.put("nbf", NOW + 59);
        claims.put("exp", NOW + 120);
        assertDoesNotThrow(() -> users.validate(claims));
    }

    @Test
    void rejectsIatOrNbfTooFarInFutureAndExpiredOrInvertedLifetime() {
        assertRejectedWith("iat", NOW + 61);
        assertRejectedWith("nbf", NOW + 61);
        assertRejectedWith("exp", NOW - 61);
        Map<String, Object> inverted = userClaims();
        inverted.put("exp", inverted.get("nbf"));
        assertThrows(IllegalArgumentException.class, () -> users.validate(inverted));
    }

    @Test
    void rejectsUnexpectedIssuerAudienceAndTenant() {
        assertRejectedWith("iss", "https://login.example.invalid/");
        assertRejectedWith("aud", "api://wrong");
        assertRejectedWith("tid", "99999999-9999-4999-8999-999999999999");
    }

    @Test
    void rejectsMissingAndMalformedTemporalClaims() {
        for (String claim : List.of("iat", "nbf", "exp")) {
            Map<String, Object> missing = userClaims();
            missing.remove(claim);
            assertThrows(IllegalArgumentException.class, () -> users.validate(missing));
            Map<String, Object> malformed = userClaims();
            malformed.put(claim, "not-a-number");
            assertThrows(IllegalArgumentException.class, () -> users.validate(malformed));
            Map<String, Object> fractional = userClaims();
            fractional.put(claim, NOW + 0.5d);
            assertThrows(IllegalArgumentException.class, () -> users.validate(fractional));
        }
    }

    @Test
    void rejectsMissingMalformedAndInsufficientGroups() {
        assertRejectedWith("groups", List.of());
        assertRejectedWith("groups", List.of(123));
        Map<String, Object> missing = userClaims();
        missing.remove("groups");
        assertThrows(IllegalArgumentException.class, () -> users.validate(missing));
    }

    @Test
    void rejectsGroupOverageInsteadOfAssumingMembership() {
        Map<String, Object> hasGroups = userClaims();
        hasGroups.remove("groups");
        hasGroups.put("hasgroups", true);
        assertThrows(IllegalArgumentException.class, () -> users.validate(hasGroups));

        Map<String, Object> distributed = userClaims();
        distributed.remove("groups");
        distributed.put("_claim_names", Map.of("groups", "src1"));
        assertThrows(IllegalArgumentException.class, () -> users.validate(distributed));
    }

    @Test
    void rejectsAppOnlyTokenWhereUserExpectedAndUserTokenWhereWorkloadExpected() {
        Map<String, Object> app = workloadClaims();
        assertThrows(IllegalArgumentException.class, () -> users.validate(app));
        ReferenceGatewayClaimsPolicy workloads = policy(ReferenceGatewayClaimsPolicy.SubjectType.WORKLOAD);
        assertThrows(IllegalArgumentException.class, () -> workloads.validate(userClaims()));
        assertDoesNotThrow(() -> workloads.validate(app));
    }

    @Test
    void iatValidationDoesNotClaimReplayPreventionAndReusedClaimsStillValidate() {
        Map<String, Object> sameBearerClaims = userClaims();
        assertDoesNotThrow(() -> users.validate(sameBearerClaims));
        assertDoesNotThrow(() -> users.validate(sameBearerClaims));
        assertFalse(users.providesReplayPrevention());
    }

    private void assertRejectedWith(String claim, Object value) {
        Map<String, Object> claims = userClaims();
        claims.put(claim, value);
        assertThrows(IllegalArgumentException.class, () -> users.validate(claims));
    }

    private static ReferenceGatewayClaimsPolicy policy(ReferenceGatewayClaimsPolicy.SubjectType type) {
        return new ReferenceGatewayClaimsPolicy(
                "https://login.microsoftonline.com/22222222-2222-4222-8222-222222222222/v2.0",
                "api://11111111-1111-4111-8111-111111111111",
                "22222222-2222-4222-8222-222222222222",
                type == ReferenceGatewayClaimsPolicy.SubjectType.USER ? Set.of("group-allowed") : Set.of(),
                type, Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC), Duration.ofSeconds(60));
    }

    private static Map<String, Object> userClaims() {
        Map<String, Object> claims = baseClaims();
        claims.put("oid", "user-object-id");
        claims.put("scp", "Mcp.Access");
        claims.put("groups", List.of("group-allowed"));
        return claims;
    }

    private static Map<String, Object> workloadClaims() {
        Map<String, Object> claims = baseClaims();
        claims.put("idtyp", "app");
        claims.put("roles", List.of("Mcp.Invoke"));
        return claims;
    }

    private static Map<String, Object> baseClaims() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("iss", "https://login.microsoftonline.com/22222222-2222-4222-8222-222222222222/v2.0");
        claims.put("aud", "api://11111111-1111-4111-8111-111111111111");
        claims.put("tid", "22222222-2222-4222-8222-222222222222");
        claims.put("iat", NOW - 30);
        claims.put("nbf", NOW - 30);
        claims.put("exp", NOW + 300);
        return claims;
    }
}
