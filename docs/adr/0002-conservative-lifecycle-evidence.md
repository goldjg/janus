# ADR 0002: Conservative lifecycle evidence

- Status: accepted
- Date: 2026-08-30

## Context

Entra application objects expose creation metadata but no universally reliable
`lastUsedAt` field. Sign-in and audit records have licence, retention and
latency constraints. Authentication or token issuance is also only a proxy for
gateway use.

## Decision

Cleanup is dry-run by default and evaluates only positively marked JANUS-owned
applications. Every decision has a machine-readable reason. Explicit exclusion
markers always retain an application.

Automatic deletion requires trustworthy activity evidence showing inactivity
beyond the configured retention period plus a final pre-delete recheck. When
activity evidence is unavailable or ambiguous, cleanup retains the application.

Creation age alone is not an implemented deletion fallback. Each run has a
deletion limit.

## Consequences

- Default retention is 30 days since reliable last observed use, not 30 days
  since creation.
- New and never-used applications are retained when no trustworthy activity
  source is available.
- Sign-in-log permissions are optional and materially expand read access;
  `Application.ReadWrite.OwnedBy` remains sufficient for conservative
  creation-only retention.
- Cleanup cannot claim to know whether a token reached the MCP gateway.
