# JANUS Microsoft Entra Permissions

## Required Graph permission

JANUS requires exactly one Microsoft Graph application permission assigned to its Managed Identity:

| Permission | Type | Justification |
|---|---|---|
| `Application.ReadWrite.OwnedBy` | Application | Create and manage only the app registrations JANUS owns |

This permission allows JANUS to:
- Create new application registrations (JANUS becomes the owner)
- Read, update, and delete application registrations that JANUS owns
- List applications filtered to JANUS-owned registrations

This permission does **not** allow JANUS to:
- Read or modify application registrations owned by other applications or users
- Read or modify service principals
- Access directory data (users, groups, devices)
- Issue tokens or act as an authorization server

## Why not Application.ReadWrite.All?

`Application.ReadWrite.All` grants read and write access to **all** application registrations and service principals in the tenant. This is excessive for JANUS, which only needs to manage the registrations it creates. `Application.ReadWrite.OwnedBy` provides the same create-and-manage capability with a significantly narrower blast radius.

## Role assignment

The bootstrap script assigns the Graph app role to the Managed Identity's service principal:

```powershell
# Find the Graph service principal
$graphSp = Get-MgServicePrincipal -Filter "appId eq '00000003-0000-0000-c000-000000000000'"

# Find the Application.ReadWrite.OwnedBy app role
$appRole = $graphSp.AppRoles | Where-Object { $_.Value -eq 'Application.ReadWrite.OwnedBy' }

# Assign to the JANUS managed identity service principal
New-MgServicePrincipalAppRoleAssignment `
  -ServicePrincipalId $janusMiServicePrincipalId `
  -PrincipalId $janusMiServicePrincipalId `
  -ResourceId $graphSp.Id `
  -AppRoleId $appRole.Id
```

Admin consent is required (built into the role assignment step above).

## What JANUS does NOT request

| Permission | Why not needed |
|---|---|
| `Application.ReadWrite.All` | Too broad; JANUS manages only owned registrations |
| `Directory.ReadWrite.All` | JANUS does not need directory-level access |
| `User.Read.All` | JANUS does not enumerate or read users |
| `Group.Read.All` | JANUS does not enumerate groups |
| Any delegated permission | JANUS operates with application identity only |
| `AppRoleAssignment.ReadWrite.All` | JANUS does not assign roles to created registrations |

## API calls JANUS makes

| Operation | Graph endpoint | Method | Permission |
|---|---|---|---|
| Create app registration | `/v1.0/applications` | POST | `Application.ReadWrite.OwnedBy` |
| List JANUS-managed apps | `/v1.0/applications?$filter=tags/any(...)` | GET | `Application.ReadWrite.OwnedBy` |
| Delete stale app | `/v1.0/applications/{id}` | DELETE | `Application.ReadWrite.OwnedBy` |

JANUS does not:
- Call `/v1.0/servicePrincipals`
- Call `/v1.0/users` or `/v1.0/groups`
- Call token or JWKS endpoints
- Make any Microsoft Graph call that requires permissions beyond `Application.ReadWrite.OwnedBy`

## Token handling

JANUS acquires a Managed Identity token for `https://graph.microsoft.com` using the Azure Identity SDK (`DefaultAzureCredential`). This token:

- Is acquired from the Azure IMDS endpoint (platform-managed)
- Is not stored, cached beyond the SDK cache, or returned to any caller
- Is never logged at any log level
- Is used only for outbound Graph calls from JANUS

JANUS does not request or handle tokens for any other resource, including the MCP gateway resource.
