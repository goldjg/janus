# Application-registration lifecycle

JANUS lifecycle decisions are intentionally conservative. A display name is
never proof of ownership, and creation age alone is never proof that an
application is inactive.

## Ownership markers

Each created application receives all of these tags:

- `janus-managed`
- `janus-lifecycle:v1`
- `janus-realm:<realm>`
- `janus-tenant:<tenant-uuid>`
- `janus-correlation:<request-uuid>` (operational reconciliation; not ownership by itself)

It is also created by the runtime managed identity using
`Application.ReadWrite.OwnedBy`. Cleanup requires every marker to match the
configured realm and tenant and requires valid immutable Graph object and
client IDs. Naming and `notes` are operational context only.

Add `janus-cleanup:exclude` to manually retain a managed application.

## What “last use” means

Entra application objects do not expose an authoritative `lastUsedAt` property.
JANUS treats a suitable Entra sign-in record as evidence that the generated
client participated in authentication or token issuance. That does not prove
the token reached or was accepted by the MCP gateway.

Sign-in reporting has permission, licensing, latency, and retention limits.
If the reporting window does not cover the relevant period, “no record found”
is not reliable evidence of no use. Group or gateway access logs answer
different questions and must not be silently conflated.

The initial repository contains the deletion policy and job, but deliberately
does not grant `AuditLog.Read.All` or pretend that a timestamp exists. A trusted
observer must write exactly one fresh evidence set after complete coverage:

- `janus-use-observed-through:<ISO-8601-instant>`
- either `janus-last-observed-use:<ISO-8601-instant>` or
  `janus-no-use-observed`

Until such an observer is deployed and reviewed, evidence is unavailable and
every application is retained. This is the conservative fallback.

## Decision algorithm

At each scheduled run the job:

1. Lists applications filtered by the realm marker and follows validated,
   bounded Graph pagination.
2. Retains objects missing any positive ownership marker, valid identifiers,
   or a trustworthy creation time.
3. Retains manually excluded and recently created objects.
4. Retains missing, duplicated, contradictory, stale, future-dated, or
   out-of-range activity evidence.
5. Marks an object as a candidate only when creation is older than retention,
   evidence coverage is fresh, and last observed use is older than retention
   or complete observation found no use.
6. In dry-run, logs `would_delete` and changes nothing.
7. In destructive mode, re-fetches by immutable Graph object ID, verifies the
   client ID did not change, repeats the full decision, and applies the bounded
   delete-attempt limit.
8. Treats `404` as an idempotent already-absent result and reports other errors.

The default retention is 30 days, maximum evidence age is 48 hours, cleanup is
dry-run, and one run attempts at most 20 deletions in the supplied Bicep.

## Configuration

| Variable | Default | Meaning |
|---|---:|---|
| `JANUS_TENANT_ID` | required | Tenant ownership boundary |
| `JANUS_REALM` | required | Realm ownership boundary |
| `JANUS_CLEANUP_RETENTION_DAYS` | `30` | Age since reliable last use / complete no-use evidence |
| `JANUS_CLEANUP_DRY_RUN` | `true` | `false` is required for deletion |
| `JANUS_CLEANUP_MAX_DELETE_COUNT` | `10` in code, `20` in Bicep | Per-run delete-attempt circuit breaker |
| `JANUS_CLEANUP_EVIDENCE_MAX_AGE_HOURS` | `48` | Maximum age of observer coverage |

Every decision is emitted as structured JSON with correlation ID, object ID,
client ID, outcome, reason, and non-sensitive detail. Tokens and sign-in record
bodies are never logged.

## Races and recovery

List/evaluate/delete is inherently racy. Re-fetch and ID-continuity checks
reduce risk but do not create a transaction with Entra. When uncertain, retain.
Do not recreate a deleted application and assume its client ID, consent, or
assignments can be recovered. See [operations.md](operations.md) before enabling
destructive cleanup.
