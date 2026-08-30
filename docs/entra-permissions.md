# Microsoft Entra and Graph permissions

## Identity separation

JANUS uses separate identities for separate authority:

| Identity | Purpose | Authority |
|---|---|---|
| Runtime user-assigned managed identity | Provision and conservatively clean JANUS-owned applications | Microsoft Graph application permission below; ACR pull if used |
| GitHub deployment service principal | Deploy Azure resources | Federated OIDC credential and resource-group-scoped Azure RBAC |
| Bootstrap administrator | One-time tenant configuration and admin consent | Interactive delegated administrator session; no reusable credential stored by JANUS |
| Generated MCP client | Authenticate a user directly with Entra | Public client; configured gateway delegated scope only; no consent or assignment grant |

Azure RBAC and Microsoft Graph permissions are different control planes. An
Azure `Contributor` assignment does not grant directory/Graph authority.

Inside Container Apps, JANUS obtains the Graph control-plane token from the
platform-injected local `IDENTITY_ENDPOINT` using the rotating
`IDENTITY_HEADER` and explicit user-assigned `AZURE_CLIENT_ID`. It does not use
a client secret, and it rejects non-loopback identity endpoints.

## Runtime Graph permission

| Permission | Type | Admin consent | Purpose |
|---|---|---|---|
| `Application.ReadWrite.OwnedBy` | Application | Required | Create public applications and read/update/delete only applications owned by the runtime identity |

Permission ID: `18a4783c-866b-4cc7-a460-3d5e5662c884`.

Microsoft documents that the permission can create and fully manage owned
applications and service principals. It also permits `GET /applications` and
`GET /servicePrincipals` tenant-wide, so enumeration must remain narrowly
filtered and response data must not be logged:
[Microsoft Graph permissions reference](https://learn.microsoft.com/graph/permissions-reference#applicationreadwriteownedby).

This permission can technically manage credentials on owned applications.
JANUS never calls credential endpoints and its payload contract asserts that no
password or key credentials are present.

## Operations

| Operation | Endpoint | Authority |
|---|---|---|
| Create application | `POST /v1.0/applications` | `Application.ReadWrite.OwnedBy` |
| List marked candidates | `GET /v1.0/applications?...` | `Application.ReadWrite.OwnedBy` |
| Re-read candidate | `GET /v1.0/applications/{id}` | `Application.ReadWrite.OwnedBy` |
| Delete eligible application | `DELETE /v1.0/applications/{id}` | `Application.ReadWrite.OwnedBy` |

JANUS does not create OAuth permission grants, app-role assignments, user/group
assignments, passwords, certificates, service principals for the gateway, or
Conditional Access policy.

## Optional lifecycle activity evidence

Microsoft Graph sign-in reports can provide authentication/token-activity
evidence via `auditLogs/signIns`, but they have licence, permission, retention
and reporting-latency constraints. Microsoft documents these dependencies in
the [Entra audit logs API overview](https://learn.microsoft.com/graph/api/resources/azure-ad-auditlog-overview).

If an operator enables a sign-in evidence provider, grant its documented
read-only reporting permission to a separately reviewed identity where
practical. Do not silently add reporting permissions to the provisioning
identity. Authentication evidence is not proof of MCP gateway admission.

Without reliable activity evidence, default cleanup retains the application.

## Explicitly forbidden runtime permissions

- `Application.ReadWrite.All`
- `Directory.ReadWrite.All`
- `AppRoleAssignment.ReadWrite.All`
- `DelegatedPermissionGrant.ReadWrite.All`
- `RoleManagement.ReadWrite.Directory`
- user-delegated Graph permissions
- any long-lived Graph client secret

The bootstrap administrator may need delegated authority to assign the single
runtime app role. That interactive authority is not granted to JANUS or GitHub.

## Generated-client gateway access

The generated application may declare only configured gateway delegated scope
IDs in `requiredResourceAccess`. This declaration does not grant consent.
Gateway service-principal assignment, group admission and consent remain
separate administrator-controlled steps.

## Audit and review

- Review runtime app-role assignments after bootstrap and periodically.
- Alert on any added Graph permission or credential on the runtime identity.
- Audit creation/deletion using correlation ID and Entra object ID.
- Review generated applications for credentials or unrelated API permissions.
- Remove the Graph assignment during decommissioning after cleanup policy is
  disabled and existing client-retention decisions are complete.
