package io.github.goldjg.janus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LifecyclePolicyTest {

    private static final String TENANT_ID = "22222222-2222-4222-8222-222222222222";
    private static final Instant NOW = Instant.parse("2026-08-29T12:00:00Z");
    private LifecyclePolicy policy;

    @BeforeEach
    void setUp() {
        policy = new LifecyclePolicy(new LifecycleConfig(TENANT_ID, "janus",
                Duration.ofDays(30), true, 10, Duration.ofHours(48)));
    }

    @Test
    void evaluate_retainsNameMatchWithoutAllOwnershipMarkers() {
        var app = application(NOW.minus(Duration.ofDays(90)), List.of(
                GraphClientService.TAG_JANUS_MANAGED,
                GraphClientService.TAG_REALM_PREFIX + "janus"));
        app.displayName = "janus-looks-owned";

        LifecycleDecision decision = policy.evaluate(app, NOW);

        assertEquals(LifecycleDecision.Reason.NOT_POSITIVELY_IDENTIFIED, decision.reason());
        assertFalse(decision.isDeleteCandidate());
    }

    @Test
    void evaluate_retainsApplicationOwnedByDifferentTenant() {
        var app = application(NOW.minus(Duration.ofDays(90)), baseTags());
        app.tags = replaceTag(app.tags, GraphClientService.TAG_TENANT_PREFIX,
                GraphClientService.TAG_TENANT_PREFIX + "99999999-9999-4999-8999-999999999999");

        assertEquals(LifecycleDecision.Reason.NOT_POSITIVELY_IDENTIFIED,
                policy.evaluate(app, NOW).reason());
    }

    @Test
    void evaluate_manualExclusionAlwaysRetains() {
        List<String> tags = new ArrayList<>(baseTags());
        tags.add(GraphClientService.TAG_CLEANUP_EXCLUDED);
        var app = application(NOW.minus(Duration.ofDays(90)), tags);

        assertEquals(LifecycleDecision.Reason.MANUALLY_EXCLUDED,
                policy.evaluate(app, NOW).reason());
    }

    @Test
    void evaluate_recentNeverObservedApplicationRetains() {
        var app = application(NOW.minus(Duration.ofDays(10)), baseTags());

        assertEquals(LifecycleDecision.Reason.RECENTLY_CREATED,
                policy.evaluate(app, NOW).reason());
    }

    @Test
    void evaluate_oldApplicationWithoutEvidenceRetainsAsUncertain() {
        var app = application(NOW.minus(Duration.ofDays(90)), baseTags());

        LifecycleDecision decision = policy.evaluate(app, NOW);

        assertEquals(LifecycleDecision.Reason.USE_EVIDENCE_UNAVAILABLE, decision.reason());
        assertEquals(LastUseEvidence.Status.UNAVAILABLE, decision.lastUseEvidence().status());
    }

    @Test
    void evaluate_staleObservationCoverageRetains() {
        var app = application(NOW.minus(Duration.ofDays(90)), evidenceTags(
                GraphClientService.TAG_LAST_OBSERVED_USE_PREFIX + NOW.minus(Duration.ofDays(60)),
                NOW.minus(Duration.ofDays(5))));

        assertEquals(LifecycleDecision.Reason.USE_EVIDENCE_STALE,
                policy.evaluate(app, NOW).reason());
    }

    @Test
    void evaluate_recentLastUseRetainsActiveClient() {
        var app = application(NOW.minus(Duration.ofDays(90)), evidenceTags(
                GraphClientService.TAG_LAST_OBSERVED_USE_PREFIX + NOW.minus(Duration.ofDays(2)),
                NOW.minus(Duration.ofHours(1))));

        assertEquals(LifecycleDecision.Reason.ACTIVE_CLIENT,
                policy.evaluate(app, NOW).reason());
    }

    @Test
    void evaluate_oldLastUseWithFreshCoverageIsDeleteCandidate() {
        var app = application(NOW.minus(Duration.ofDays(90)), evidenceTags(
                GraphClientService.TAG_LAST_OBSERVED_USE_PREFIX + NOW.minus(Duration.ofDays(60)),
                NOW.minus(Duration.ofHours(1))));

        LifecycleDecision decision = policy.evaluate(app, NOW);

        assertEquals(LifecycleDecision.Reason.EXPIRED_SINCE_LAST_USE, decision.reason());
        assertTrue(decision.isDeleteCandidate());
    }

    @Test
    void evaluate_explicitNoUseWithFreshCoverageIsDeleteCandidate() {
        List<String> tags = evidenceTags(GraphClientService.TAG_NO_USE_OBSERVED,
                NOW.minus(Duration.ofHours(1)));
        var app = application(NOW.minus(Duration.ofDays(90)), tags);

        LifecycleDecision decision = policy.evaluate(app, NOW);

        assertEquals(LifecycleDecision.Reason.EXPIRED_NEVER_USED, decision.reason());
        assertTrue(decision.isDeleteCandidate());
    }

    @Test
    void evaluate_ambiguousNoUseAndLastUseMarkersRetains() {
        List<String> tags = evidenceTags(GraphClientService.TAG_NO_USE_OBSERVED,
                NOW.minus(Duration.ofHours(1)));
        tags.add(GraphClientService.TAG_LAST_OBSERVED_USE_PREFIX + NOW.minus(Duration.ofDays(60)));
        var app = application(NOW.minus(Duration.ofDays(90)), tags);

        assertEquals(LifecycleDecision.Reason.USE_EVIDENCE_INVALID,
                policy.evaluate(app, NOW).reason());
    }

    private static GraphClientService.GraphApplicationResponse application(
            Instant createdAt,
            List<String> tags) {
        var app = new GraphClientService.GraphApplicationResponse();
        app.id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
        app.appId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
        app.createdDateTime = createdAt.toString();
        app.tags = new ArrayList<>(tags);
        return app;
    }

    private static List<String> baseTags() {
        return List.of(
                GraphClientService.TAG_JANUS_MANAGED,
                GraphClientService.TAG_LIFECYCLE_SCHEMA,
                GraphClientService.TAG_REALM_PREFIX + "janus",
                GraphClientService.TAG_TENANT_PREFIX + TENANT_ID);
    }

    private static List<String> evidenceTags(String resultTag, Instant observedThrough) {
        List<String> tags = new ArrayList<>(baseTags());
        tags.add(resultTag);
        tags.add(GraphClientService.TAG_USE_OBSERVED_THROUGH_PREFIX + observedThrough);
        return tags;
    }

    private static List<String> replaceTag(List<String> tags, String prefix, String replacement) {
        List<String> replaced = new ArrayList<>();
        tags.stream().filter(tag -> !tag.startsWith(prefix)).forEach(replaced::add);
        replaced.add(replacement);
        return replaced;
    }
}
