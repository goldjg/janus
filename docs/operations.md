# Operations and recovery runbook

## Safety model

JANUS is registration-plane infrastructure. If registration is unhealthy,
existing Entra clients and gateway tokens continue to operate independently.
Prefer disabling new registration over weakening policy.

## Routine checks

- Confirm the Container App revision is healthy and immutable image digest is
  the expected deployment output.
- Monitor admitted, rejected, throttled and failed registration counts.
- Alert on unusual registration rate, Graph 429/5xx responses and cleanup
  delete/error counts.
- Review managed-identity permissions and GitHub federated credentials after
  organisational or repository changes.
- Run cleanup in dry-run and review every decision before enabling deletion.

Never collect bearer tokens, authorization codes, refresh tokens, client
secrets or full JWTs in logs or diagnostic bundles.

## Registration incident

1. Disable or narrow DCR ingress/admission; do not alter gateway validation.
2. Preserve correlation IDs and sanitized JANUS/Graph outcome logs.
3. Inventory applications using both explicit JANUS markers and managed
   identity ownership. Never inventory by display name alone.
   Use the unique `janus-correlation:<request-uuid>` tag to reconcile a Graph
   create whose network outcome was ambiguous; JANUS does not automatically
   retry ambiguous create failures.
4. Revoke the runtime Graph permission if managed-identity compromise is
   suspected.
5. Rotate admission credentials and redeploy an immutable known-good image.
6. Review Entra audit/sign-in evidence subject to tenant retention limits.

Existing gateway authorization should remain fail-closed and independent.

## Failed deployment

Azure Container Apps revisions allow traffic to remain on a prior healthy
revision. Restore the previous immutable image/reference and verify health
before shifting traffic. A database migration or destructive schema step is
outside the normal JANUS deployment and requires a separate recovery plan.

## Cleanup recovery

- Dry-run is the default; a dry-run has no deletion rollback requirement.
- Before enabling deletion, export object IDs, app IDs, markers, creation time,
  activity evidence and decision reasons to the protected operational log.
- Entra application deletion recovery capabilities and retention are platform
  dependent; do not treat them as the primary rollback mechanism.
- If a delete run behaves unexpectedly, disable the scheduled job, revoke its
  Graph permission if necessary, preserve logs and investigate before rerun.
- Never recreate an application and assume the old client ID or assignments
  will be restored.

## Removal

1. Disable DCR admission and wait for in-flight requests to finish.
2. Disable the cleanup schedule.
3. Decide explicitly whether JANUS-created Entra applications must remain for
   existing clients; infrastructure removal must not implicitly delete them.
4. Remove the Azure deployment resources using a reviewed deployment/what-if.
5. Remove the runtime managed identity's Graph app-role assignment.
6. Remove GitHub federated credentials and Azure RBAC assignments.
7. Remove the bootstrap-created deployment application/service principal only
   after confirming no other environment uses it.
8. Retain audit records according to organisational policy.

## Live validation boundary

Live Graph, Entra, gateway and cleanup tests are opt-in. Never run them for
untrusted pull requests. Use a dedicated test tenant, bounded object names and
an explicit teardown inventory.
