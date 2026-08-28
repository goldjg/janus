# JANUS Architecture

## Overview

JANUS is a narrow interoperability adapter. It exposes an [RFC 7591](https://datatracker.ietf.org/doc/html/rfc7591) Dynamic Client Registration (DCR) endpoint to MCP clients, then translates each DCR request into a real Microsoft Entra ID application registration via Microsoft Graph. Once registration is complete, JANUS steps out of the authentication path entirely.

## Component diagram

```
┌────────────────────────────────────────────────────────────────────┐
│                          MCP Client                                │
│  (Claude Code, Cursor, custom agent, or any RFC 7591 DCR client)  │
└───────────────────────────────┬────────────────────────────────────┘
                                │  (1) POST DCR request
                                ▼
┌────────────────────────────────────────────────────────────────────┐
│                     Azure Container Apps                           │
│                                                                    │
│  ┌─────────────────────────────────────────────────────────────┐  │
│  │  Keycloak (quay.io/keycloak/keycloak)                       │  │
│  │                                                             │  │
│  │  /realms/janus/clients-registrations/openid-connect         │  │
│  │       │                                                     │  │
│  │       ▼                                                     │  │
│  │  JanusDCRProvider (janus-dcr-provider.jar)                  │  │
│  │       │                                                     │  │
│  │       ├──► RegistrationPolicy.validate()                   │  │
│  │       │      redirect URIs, grant types, scopes,           │  │
│  │       │      client name, auth method, field limits        │  │
│  │       │                                                     │  │
│  │       └──► GraphClientService.createApplication()          │  │
│  │              Managed Identity credential                   │  │
│  └─────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  User-Assigned Managed Identity                                    │
└───────────────────────────────┬────────────────────────────────────┘
                                │  (3) POST /v1.0/applications
                                │      Application.ReadWrite.OwnedBy
                                ▼
┌────────────────────────────────────────────────────────────────────┐
│                     Microsoft Entra ID                             │
│                                                                    │
│  Creates public client app registration:                          │
│  - isFallbackPublicClient: true                                   │
│  - no client secrets / certificates                               │
│  - redirect URIs from policy allowlist                            │
│  - JANUS ownership tags                                           │
│                                                                    │
│  (4) Returns appId (client_id)                                    │
└───────────────────────────────┬────────────────────────────────────┘
                                │  (4) client_id in DCR response
                                ▼
                          MCP Client

  ─────── JANUS is now out of the authentication path ───────

  (5) MCP client → Entra: authorization request (auth code + PKCE)
  (6) Entra → MCP client: authorization code
  (7) MCP client → Entra: token request
  (8) Entra → MCP client: access token (Entra-issued, Entra-signed)
  (9) MCP client → MCP gateway: API call with Entra bearer token
  (10) MCP gateway validates token (issuer, aud, sig, exp) independently
```

## Separation of concerns

| Concern | Component | Notes |
|---|---|---|
| DCR protocol surface | Keycloak + JanusDCRProvider | Keycloak provides the OIDC-conformant endpoint and session model |
| Registration policy | RegistrationPolicy | Pure validation; no side effects |
| Entra integration | GraphClientService | One outbound call per registration |
| Authentication | Microsoft Entra ID | No JANUS involvement after DCR |
| Token issuance | Microsoft Entra ID | JANUS never touches tokens |
| Gateway authorisation | MCP gateway | Independent of JANUS |
| App registration cleanup | Cleanup Container Apps Job | Scheduled; uses same managed identity |

## Data flows

### Registration flow (DCR)

```
MCP Client
  │  POST /realms/janus/clients-registrations/openid-connect
  │  Body: { client_name, redirect_uris, grant_types, … }
  ▼
Keycloak routes to JanusDCRProvider.create()
  │
  ├─ RegistrationPolicy.validate(request)
  │    Returns 400 if invalid
  │
  └─ GraphClientService.createApplication(clientName, redirectUris)
       │
       │  POST https://graph.microsoft.com/v1.0/applications
       │  Authorization: ******
       │  Body: {
       │    displayName: "janus-<realm>-<sanitised-name>-<uuid>",
       │    isFallbackPublicClient: true,
       │    publicClient: { redirectUris: [...] },
       │    tags: ["janus-managed", "janus-realm:<realm>"],
       │    notes: "Created by JANUS. Realm: <realm>. Created: <ISO8601>."
       │  }
       ▼
  Entra returns appId
  │
  └─ JanusDCRProvider returns DCR response:
       { client_id: <appId>, client_name: <displayName>, … }
```

### Cleanup flow

```
Container Apps Job (schedule: cron)
  │
  └─ GraphClientService.listJanusApplications(realm)
       GET /v1.0/applications?$filter=tags/any(t:t eq 'janus-managed')
       │
       ├─ For each app older than retention period:
       │    DELETE /v1.0/applications/<objectId>
       │
       └─ Log each deletion (correlationId, objectId, appId, age)
```

## Deployment topology

```
Azure Resource Group
  │
  ├── User-Assigned Managed Identity
  │     └── Graph app role: Application.ReadWrite.OwnedBy
  │
  ├── Azure Container Registry
  │     └── Image: janus-keycloak:<tag>
  │
  ├── Container Apps Environment
  │     ├── Container App: keycloak
  │     │     - Image: janus-keycloak:<tag>
  │     │     - Ingress: HTTPS external
  │     │     - Identity: user-assigned managed identity
  │     │     - Env: JANUS_TENANT_ID, JANUS_GATEWAY_RESOURCE_URI, …
  │     │
  │     └── Container App Job: janus-cleanup
  │           - Image: janus-keycloak:<tag>  (reuses same image, different entrypoint)
  │           - Schedule: 0 2 * * *  (daily at 02:00 UTC)
  │           - Identity: user-assigned managed identity
  │
  └── Azure Monitor / Log Analytics Workspace
```

## Security architecture

See [security-model.md](security-model.md) and [threat-model.md](threat-model.md).

## Trust boundaries

| Boundary | Crossing point | Controls |
|---|---|---|
| Internet → Keycloak DCR endpoint | Container Apps ingress | TLS termination, rate limiting |
| DCR input → JANUS policy | RegistrationPolicy | Input validation, allowlists, field limits |
| JANUS → Microsoft Graph | Managed Identity + TLS | `Application.ReadWrite.OwnedBy` only; no user-delegated permissions |
| MCP client → Entra | Direct; JANUS not involved | Standard Entra CA/MFA/device policies |
| MCP client → MCP gateway | Direct; JANUS not involved | Gateway validates Entra token independently |

## Key design constraints

1. **JANUS does not issue access tokens.** Never. The JANUS security invariant is absolute.
2. **Public clients only.** JANUS-created registrations never have client secrets or certificates.
3. **Owned registrations only.** `Application.ReadWrite.OwnedBy` constrains JANUS to only manage registrations it created.
4. **No user context.** JANUS operates with application identity (Managed Identity), never with a user context.
5. **Keycloak is a transport, not an authority.** Keycloak provides the DCR endpoint surface; JANUS controls policy and Entra integration. Keycloak's own client registry is not used for gateway access.
6. **Single-tenant by default.** Multi-tenant is not supported without explicit design justification and documentation.
