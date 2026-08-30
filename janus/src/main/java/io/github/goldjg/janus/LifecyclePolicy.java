package io.github.goldjg.janus;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Pure, conservative lifecycle policy for JANUS-managed applications.
 *
 * <p>The policy never uses display-name conventions. Deletion eligibility
 * requires all ownership markers, trustworthy creation time, current use
 * observation coverage, and either an old last-observed-use timestamp or an
 * explicit no-use observation. Absence and ambiguity always retain.
 */
public final class LifecyclePolicy {

    private static final Duration FUTURE_CLOCK_TOLERANCE = Duration.ofMinutes(5);

    private final LifecycleConfig config;

    /** Create a lifecycle policy from validated configuration. */
    public LifecyclePolicy(LifecycleConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * Evaluate one Graph application at a fixed instant.
     *
     * @param application Graph application projection
     * @param now evaluation time
     * @return auditable retain/delete-candidate result
     */
    public LifecycleDecision evaluate(
            GraphClientService.GraphApplicationResponse application,
            Instant now) {
        Objects.requireNonNull(now, "now");
        if (!GraphClientService.isPositivelyJanusManaged(
                application, config.realm(), config.tenantId())) {
            return retain(LifecycleDecision.Reason.NOT_POSITIVELY_IDENTIFIED, null,
                    LastUseEvidence.unavailable("ownership_markers_missing"));
        }

        if (!GraphClientService.hasValidApplicationIdentifiers(application)) {
            return retain(LifecycleDecision.Reason.INVALID_IDENTIFIERS, null,
                    LastUseEvidence.invalid("application_identifiers_invalid"));
        }

        List<String> tags = application.tags == null ? List.of() : application.tags;
        if (tags.contains(GraphClientService.TAG_CLEANUP_EXCLUDED)) {
            return retain(LifecycleDecision.Reason.MANUALLY_EXCLUDED, null,
                    LastUseEvidence.unavailable("manual_exclusion_marker"));
        }

        if (application.createdDateTime == null || application.createdDateTime.isBlank()) {
            return retain(LifecycleDecision.Reason.MISSING_CREATED_TIME, null,
                    LastUseEvidence.unavailable("created_time_missing"));
        }

        final Instant createdAt;
        try {
            createdAt = Instant.parse(application.createdDateTime);
        } catch (DateTimeException e) {
            return retain(LifecycleDecision.Reason.INVALID_CREATED_TIME, null,
                    LastUseEvidence.invalid("created_time_invalid"));
        }

        if (createdAt.isAfter(now.plus(FUTURE_CLOCK_TOLERANCE))) {
            return retain(LifecycleDecision.Reason.CREATED_TIME_IN_FUTURE, createdAt,
                    LastUseEvidence.invalid("created_time_in_future"));
        }

        Instant cutoff = now.minus(config.retention());
        if (createdAt.isAfter(cutoff)) {
            return retain(LifecycleDecision.Reason.RECENTLY_CREATED, createdAt,
                    LastUseEvidence.unavailable("use_evidence_not_required_for_recent_client"));
        }

        LastUseEvidence evidence = evidenceFromTags(tags, createdAt, now);
        if (evidence.status() == LastUseEvidence.Status.UNAVAILABLE) {
            return retain(LifecycleDecision.Reason.USE_EVIDENCE_UNAVAILABLE, createdAt, evidence);
        }
        if (evidence.status() == LastUseEvidence.Status.INVALID) {
            return retain(LifecycleDecision.Reason.USE_EVIDENCE_INVALID, createdAt, evidence);
        }
        if (evidence.observedThrough().isBefore(now.minus(config.maximumEvidenceAge()))) {
            return retain(LifecycleDecision.Reason.USE_EVIDENCE_STALE, createdAt, evidence);
        }

        if (evidence.status() == LastUseEvidence.Status.RELIABLE_NO_USE) {
            return deleteCandidate(LifecycleDecision.Reason.EXPIRED_NEVER_USED,
                    createdAt, evidence);
        }
        if (evidence.lastObservedUse().isAfter(cutoff)) {
            return retain(LifecycleDecision.Reason.ACTIVE_CLIENT, createdAt, evidence);
        }
        return deleteCandidate(LifecycleDecision.Reason.EXPIRED_SINCE_LAST_USE,
                createdAt, evidence);
    }

    private static LastUseEvidence evidenceFromTags(
            List<String> tags,
            Instant createdAt,
            Instant now) {
        List<String> observedThrough = valuesWithPrefix(
                tags, GraphClientService.TAG_USE_OBSERVED_THROUGH_PREFIX);
        List<String> lastUse = valuesWithPrefix(
                tags, GraphClientService.TAG_LAST_OBSERVED_USE_PREFIX);
        boolean noUse = tags.contains(GraphClientService.TAG_NO_USE_OBSERVED);

        if (observedThrough.isEmpty()) {
            return LastUseEvidence.unavailable("graph_sign_in_evidence_unavailable");
        }
        if (observedThrough.size() != 1 || lastUse.size() > 1 || (noUse && !lastUse.isEmpty())) {
            return LastUseEvidence.invalid("use_evidence_markers_ambiguous");
        }

        final Instant through;
        try {
            through = Instant.parse(observedThrough.get(0));
        } catch (DateTimeException e) {
            return LastUseEvidence.invalid("observed_through_invalid");
        }
        if (through.isAfter(now.plus(FUTURE_CLOCK_TOLERANCE)) || through.isBefore(createdAt)) {
            return LastUseEvidence.invalid("observed_through_out_of_range");
        }

        if (noUse) {
            return new LastUseEvidence(LastUseEvidence.Status.RELIABLE_NO_USE,
                    null, through, "no_use_observed");
        }
        if (lastUse.isEmpty()) {
            return LastUseEvidence.unavailable("last_use_result_missing");
        }

        final Instant lastUsedAt;
        try {
            lastUsedAt = Instant.parse(lastUse.get(0));
        } catch (DateTimeException e) {
            return LastUseEvidence.invalid("last_observed_use_invalid");
        }
        if (lastUsedAt.isBefore(createdAt) || lastUsedAt.isAfter(through)) {
            return LastUseEvidence.invalid("last_observed_use_out_of_range");
        }
        return new LastUseEvidence(LastUseEvidence.Status.RELIABLE_LAST_USE,
                lastUsedAt, through, "last_use_observed");
    }

    private static List<String> valuesWithPrefix(List<String> tags, String prefix) {
        return tags.stream()
                .filter(Objects::nonNull)
                .filter(tag -> tag.startsWith(prefix))
                .map(tag -> tag.substring(prefix.length()))
                .toList();
    }

    private static LifecycleDecision retain(
            LifecycleDecision.Reason reason,
            Instant createdAt,
            LastUseEvidence evidence) {
        return new LifecycleDecision(LifecycleDecision.Action.RETAIN, reason, createdAt, evidence);
    }

    private static LifecycleDecision deleteCandidate(
            LifecycleDecision.Reason reason,
            Instant createdAt,
            LastUseEvidence evidence) {
        return new LifecycleDecision(LifecycleDecision.Action.DELETE_CANDIDATE,
                reason, createdAt, evidence);
    }
}
