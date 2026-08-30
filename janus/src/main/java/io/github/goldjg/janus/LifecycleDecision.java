package io.github.goldjg.janus;

import java.time.Instant;

/**
 * Auditable cleanup decision for one Entra application registration.
 *
 * @param action whether deletion is permitted
 * @param reason stable reason code
 * @param createdAt parsed creation time, when trustworthy
 * @param lastUseEvidence evidence considered by the policy
 */
public record LifecycleDecision(
        Action action,
        Reason reason,
        Instant createdAt,
        LastUseEvidence lastUseEvidence) {

    /** Policy action. Actual deletion still requires a non-dry-run execution and re-check. */
    public enum Action {
        RETAIN,
        DELETE_CANDIDATE
    }

    /** Stable decision reasons intended for logs, metrics, and tests. */
    public enum Reason {
        NOT_POSITIVELY_IDENTIFIED,
        MANUALLY_EXCLUDED,
        INVALID_IDENTIFIERS,
        MISSING_CREATED_TIME,
        INVALID_CREATED_TIME,
        CREATED_TIME_IN_FUTURE,
        RECENTLY_CREATED,
        USE_EVIDENCE_UNAVAILABLE,
        USE_EVIDENCE_INVALID,
        USE_EVIDENCE_STALE,
        ACTIVE_CLIENT,
        EXPIRED_SINCE_LAST_USE,
        EXPIRED_NEVER_USED
    }

    /** Return whether this object is eligible for the destructive re-check. */
    public boolean isDeleteCandidate() {
        return action == Action.DELETE_CANDIDATE;
    }
}
