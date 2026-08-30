package io.github.goldjg.janus;

import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * Test-only reference gateway claims policy.
 *
 * <p>Input is assumed to come from a mature JWT verifier that has already
 * verified signature/algorithm and rejected duplicate JSON claim names. This
 * class deliberately performs no parsing or cryptography and is not JANUS runtime code.
 */
final class ReferenceGatewayClaimsPolicy {
    enum SubjectType { USER, WORKLOAD }

    private final String issuer;
    private final String audience;
    private final String tenant;
    private final Set<String> requiredGroups;
    private final SubjectType expectedSubject;
    private final Clock clock;
    private final Duration skew;

    ReferenceGatewayClaimsPolicy(String issuer, String audience, String tenant,
            Set<String> requiredGroups, SubjectType expectedSubject, Clock clock, Duration skew) {
        this.issuer = issuer;
        this.audience = audience;
        this.tenant = tenant;
        this.requiredGroups = Set.copyOf(requiredGroups);
        this.expectedSubject = expectedSubject;
        this.clock = clock;
        this.skew = skew;
    }

    void validate(Map<String, Object> claims) {
        exactString(claims, "iss", issuer);
        exactString(claims, "aud", audience);
        exactString(claims, "tid", tenant);
        long now = clock.instant().getEpochSecond();
        long allowedSkew = skew.toSeconds();
        long iat = integerClaim(claims, "iat");
        long nbf = integerClaim(claims, "nbf");
        long exp = integerClaim(claims, "exp");
        if (iat > now + allowedSkew) reject("iat is too far in the future");
        if (nbf > now + allowedSkew) reject("token is not yet valid");
        if (exp <= now - allowedSkew || exp <= nbf) reject("token is expired or has an invalid lifetime");

        boolean app = "app".equals(claims.get("idtyp"));
        boolean hasDelegatedScopes = claims.get("scp") instanceof String value && !value.isBlank();
        boolean hasAppRoles = claims.get("roles") instanceof Collection<?> roles && !roles.isEmpty();
        if (expectedSubject == SubjectType.USER
                && (app || !hasDelegatedScopes || !(claims.get("oid") instanceof String))) {
            reject("a delegated user token is required");
        }
        if (expectedSubject == SubjectType.WORKLOAD && (!app || !hasAppRoles || hasDelegatedScopes)) {
            reject("an app-only workload token is required");
        }

        if (expectedSubject == SubjectType.USER && !requiredGroups.isEmpty()) {
            if (claims.containsKey("hasgroups") || containsGroupsOveragePointer(claims.get("_claim_names"))) {
                reject("group overage requires a separate fail-closed authorization resolver");
            }
            Object groupsClaim = claims.get("groups");
            if (!(groupsClaim instanceof Collection<?> groups)
                    || !groups.stream().allMatch(String.class::isInstance)
                    || !groups.containsAll(requiredGroups)) {
                reject("required group membership is absent");
            }
        }
    }

    /** iat freshness is not a nonce store and cannot make a stateless bearer token non-replayable. */
    boolean providesReplayPrevention() {
        return false;
    }

    private static boolean containsGroupsOveragePointer(Object value) {
        return value instanceof Map<?, ?> map && map.containsKey("groups");
    }

    private static long integerClaim(Map<String, Object> claims, String name) {
        Object value = claims.get(name);
        if (!(value instanceof Number)) reject("missing or malformed " + name);
        Number number = (Number) value;
        double floating = number.doubleValue();
        long integral = number.longValue();
        if (!Double.isFinite(floating) || floating != integral) reject("missing or malformed " + name);
        return integral;
    }

    private static void exactString(Map<String, Object> claims, String name, String expected) {
        if (!(claims.get(name) instanceof String actual) || !actual.equals(expected)) {
            reject("unexpected or missing " + name);
        }
    }

    private static void reject(String reason) {
        throw new IllegalArgumentException(reason);
    }
}
