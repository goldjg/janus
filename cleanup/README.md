# JANUS Cleanup Job

The cleanup job removes stale JANUS-managed Entra application registrations.

## Implementation

The cleanup job is implemented as `CleanupJob.java` in the `janus/` Maven module:
- Source: [`../janus/src/main/java/io/github/goldjg/janus/CleanupJob.java`](../janus/src/main/java/io/github/goldjg/janus/CleanupJob.java)
- Entry point: `io.github.goldjg.janus.CleanupJob`
- Script: [`../janus/cleanup.sh`](../janus/cleanup.sh)

The cleanup code reuses `GraphClientService` and `JanusConfig` from the same module.

## Deployment

The cleanup job is deployed as an Azure Container Apps Job using the same container image as the Keycloak/JANUS service. The `cleanup.sh` script overrides the default Keycloak entrypoint to run the `CleanupJob` main class.

Deployment is handled by `infra/main.bicep`. The job runs on a cron schedule (default: daily at 02:00 UTC).

## Configuration

| Environment variable | Required | Default | Description |
|---|---|---|---|
| `JANUS_TENANT_ID` | Yes | — | Entra tenant ID |
| `JANUS_REALM` | Yes | — | Keycloak realm to clean up |
| `AZURE_CLIENT_ID` | Yes | — | Managed identity client ID |
| `JANUS_CLEANUP_RETENTION_DAYS` | No | 90 | Days before registration is considered stale |

## What it does

1. Acquires a Graph token using the Managed Identity (IMDS).
2. Lists all JANUS-managed registrations for the configured realm (`janus-realm:<realm>` tag).
3. Deletes registrations where `createdDateTime` is older than the retention period.
4. Logs each deletion with `objectId`, `appId`, `displayName`, and age.
5. Exits with code 0 on success, 1 if any deletions failed.

## Manual run

To run the cleanup job manually via the Azure portal or CLI:

```bash
az containerapp job start \
  --name <janus-cleanup-job-name> \
  --resource-group <resource-group>
```

## Audit

Cleanup activities are logged to the Container Apps Job log stream and to the
configured Log Analytics workspace. Each deletion includes:

```json
{
  "operation": "cleanup_delete",
  "correlationId": "<uuid>",
  "objectId": "<graph-object-id>",
  "appId": "<entra-app-id>",
  "displayName": "<display-name>",
  "createdAt": "<ISO8601>",
  "ageSeconds": 7776000
}
```
