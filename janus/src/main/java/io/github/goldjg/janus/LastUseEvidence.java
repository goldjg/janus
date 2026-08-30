package io.github.goldjg.janus;

import java.time.Instant;

/**
 * The lifecycle engine's knowledge of client use.
 *
 * <p>Microsoft Graph application objects do not provide an authoritative
 * {@code lastUsedAt} property. Sign-in evidence can be unavailable because of
 * permission, licensing, retention, or ingestion latency. JANUS therefore
 * represents unavailable and invalid evidence explicitly and retains the
 * application in either case.
 *
 * @param status evidence quality and meaning
 * @param lastObservedUse most recent observed authentication/token signal, if any
 * @param observedThrough latest instant through which the evidence source was checked
 * @param detail stable machine-readable detail for audit logs
 */
public record LastUseEvidence(
        Status status,
        Instant lastObservedUse,
        Instant observedThrough,
        String detail) {

    /** Evidence states understood by the conservative lifecycle policy. */
    public enum Status {
        RELIABLE_LAST_USE,
        RELIABLE_NO_USE,
        UNAVAILABLE,
        INVALID
    }

    /** Build an unavailable result without inventing a last-use timestamp. */
    public static LastUseEvidence unavailable(String detail) {
        return new LastUseEvidence(Status.UNAVAILABLE, null, null, detail);
    }

    /** Build an invalid result that must never authorize deletion. */
    public static LastUseEvidence invalid(String detail) {
        return new LastUseEvidence(Status.INVALID, null, null, detail);
    }
}
