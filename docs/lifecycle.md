# JANUS App Registration Lifecycle

## Overview

JANUS creates Entra application registrations on behalf of MCP clients. These registrations represent OAuth client identities in the tenant. JANUS manages the lifecycle of these registrations to prevent accumulation of stale entries.

## Creation

When a DCR request is successfully validated, JANUS creates an Entra application registration:

```json
{
  "displayName": "janus-<realm>-<sanitised-client-name>-<uuid-prefix>",
  "isFallbackPublicClient": true,
  "publicClient": {
    "redirectUris": ["<validated redirect URIs>"]
  },
  "signInAudience": "AzureADMyOrg",
  "tags": ["janus-managed", "janus-realm:<realm>"],
  "notes": "Created by JANUS. Realm: <realm>. Created: <ISO8601 UTC>."
}
```

### Display name format

`janus-<realm>-<sanitised-client-name>-<uuid-prefix>`

- `<realm>` — Keycloak realm name (e.g. `janus`)
- `<sanitised-client-name>` — client name from DCR request, lowercased, spaces replaced with hyphens, non-alphanumeric stripped
- `<uuid-prefix>` — first 8 characters of a random UUID (collision avoidance)

Example: `janus-janus-claude-code-a1b2c3d4`

### Ownership

`Application.ReadWrite.OwnedBy` causes JANUS's Managed Identity service principal to become the owner of each created registration. This prevents JANUS from reading or modifying registrations it did not create.

## Tagging

All JANUS-created registrations carry the tags:
- `janus-managed` — identifies JANUS-owned registrations for list queries
- `janus-realm:<realm>` — identifies which JANUS realm created the registration

Tags are used in Graph `$filter` queries by the cleanup job.

## Deletion

### Cleanup job

The cleanup Container Apps Job runs on a configurable schedule (default: daily at 02:00 UTC) and deletes JANUS-managed registrations that meet the deletion criteria.

### Deletion criteria

A registration is eligible for deletion when:
- It carries the `janus-managed` tag
- Its creation time (from the `notes` field) is older than the configured retention period (default: 90 days)

Retention period is configured via the `JANUS_CLEANUP_RETENTION_DAYS` environment variable.

### Cleanup audit log

Each cleanup run logs:

```json
{
  "operation": "cleanup",
  "correlationId": "<uuid>",
  "runAt": "<ISO8601>",
  "realm": "<realm>",
  "evaluated": 42,
  "deleted": 3,
  "errors": 0
}
```

Each deletion logs:

```json
{
  "operation": "cleanup.delete",
  "correlationId": "<uuid>",
  "objectId": "<Entra object ID>",
  "appId": "<Entra app ID>",
  "displayName": "<display name>",
  "createdAt": "<ISO8601>",
  "ageSeconds": 7776000
}
```

## Manual deletion

Administrators can delete JANUS-managed registrations manually via the Azure Portal or Azure CLI:

```bash
# List JANUS-managed registrations
az ad app list --filter "tags/any(t:t eq 'janus-managed')" --query "[].{name:displayName, appId:appId}"

# Delete by object ID
az ad app delete --id <object-id>
```

## Registration count limits

Entra tenants have limits on the number of application registrations. To avoid hitting these limits:

1. The cleanup job deletes stale registrations automatically.
2. Container Apps ingress rate limiting prevents registration floods.
3. The default retention period (90 days) balances usability with tenant hygiene.

Adjust `JANUS_CLEANUP_RETENTION_DAYS` based on your tenant limits and usage patterns.

## No update path

JANUS does not support updating an existing registration (e.g. to add redirect URIs). If an MCP client needs additional redirect URIs, it should submit a new DCR request. The old registration will be cleaned up after the retention period.

This is intentional: update paths increase complexity and attack surface without clear operational benefit for the public client use case.
