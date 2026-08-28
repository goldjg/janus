# JANUS — MCP Client Registration Broker for Microsoft Entra ID

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Build](https://github.com/goldjg/janus/actions/workflows/build.yml/badge.svg)](https://github.com/goldjg/janus/actions/workflows/build.yml)

JANUS solves a narrow interoperability problem between MCP clients and Microsoft Entra ID.

MCP clients may expect [RFC 7591 Dynamic Client Registration (DCR)](https://datatracker.ietf.org/doc/html/rfc7591). Microsoft Entra ID does not support DCR. JANUS bridges that gap without becoming an identity provider or authorization server.

**Core principle: Registration translation. Identity authority preserved.**

JANUS translates DCR requests into real Entra application registrations via Microsoft Graph. The MCP client then authenticates _directly_ against Microsoft Entra ID. JANUS never issues, caches, or touches the gateway access token.

## Security invariant

> **No JANUS component may issue, proxy, cache, exchange, or re-sign the bearer token used to access the protected MCP gateway. Microsoft Entra ID must remain the issuer of the gateway access token.**

This invariant is non-negotiable. Any implementation choice that would violate it must not be implemented.

## Architecture

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  MCP Client (Claude Code, Cursor, etc.)                                      │
│                                                                              │
│  1. POST /realms/{realm}/clients-registrations/openid-connect (DCR)          │
└─────────────────────────────────┬────────────────────────────────────────────┘
                                  │  DCR request
                                  ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  Azure Container Apps                                                        │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  Keycloak + JANUS extension (janus-dcr-provider.jar)                 │   │
│  │                                                                      │   │
│  │  JanusDCRProvider                                                    │   │
│  │    ├─► RegistrationPolicy  (validate + constrain)                   │   │
│  │    └─► GraphClientService  (create Entra app registration)          │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  Managed Identity ──────────────────────────────────────────────────────►   │
└───────────────────────────────────┬─────────────────────────────────────────┘
                                    │  POST /applications  (MS Graph)
                                    ▼
┌───────────────────────────────────────────────────────────────────────────┐
│  Microsoft Entra ID                                                        │
│                                                                            │
│  3. Creates public client app registration                                 │
│     - public client (no secret, no cert)                                  │
│     - PKCE enforced                                                        │
│     - redirect URIs from policy allowlist only                             │
│     - JANUS-managed metadata tags                                          │
│                                                                            │
│  4. Returns client_id ──────────────────────────────────────────────────► │
└────────────────────────────────────────────────────────────────────────────┘
     │
     │  5. MCP client authenticates directly against Entra (auth code + PKCE)
     │
     │  6. Entra issues access token
     │
     │  7. MCP client calls MCP gateway with Entra-issued token
     │
     │  8. MCP gateway validates token independently (issuer, audience, sig, exp)
     ▼
  JANUS is out of the authentication path entirely
```

### Components

| Component | Purpose |
|---|---|
| `janus/` | Keycloak SPI extension (Java/Maven). Implements DCR endpoint, registration policy, and Microsoft Graph integration |
| `infra/` | Bicep IaC for Azure Container Apps, Keycloak, managed identity, Container Registry, and lifecycle cleanup job |
| `bootstrap/` | PowerShell bootstrap script for one-time prerequisite setup (Graph role assignments, Keycloak realm config) |
| `cleanup/` | Azure Container Apps Job that purges stale JANUS-managed Entra app registrations |
| `.github/workflows/` | GitHub Actions CI/CD: build, test, push image, deploy to Azure |
| `examples/mcp/` | Example DCR requests and MCP client configuration snippets |
| `docs/` | Architecture, deployment, threat model, security model, and operational guides |

## What JANUS is not

- ❌ Not an OAuth authorization server
- ❌ Not an identity provider (IdP)
- ❌ Not a token broker or token cache
- ❌ Not a reverse proxy for user authentication
- ❌ Not a session service
- ❌ Not a custom OAuth implementation

## Flow summary

1. MCP client sends a DCR request to JANUS/Keycloak.
2. JANUS validates and constrains the registration request against policy.
3. JANUS calls Microsoft Graph with its Managed Identity to create a public client app registration in Entra.
4. JANUS returns the Entra `client_id` to the MCP client via the DCR response.
5. The MCP client authenticates directly against Microsoft Entra ID (auth code + PKCE).
6. Entra issues the access token.
7. The MCP gateway validates the Entra-issued token independently.

## Prerequisites

| Requirement | Notes |
|---|---|
| Azure subscription | With permission to create Container Apps, Managed Identities, and assign Graph API roles |
| Microsoft Entra ID tenant | Single tenant (multi-tenant is not supported by default) |
| Azure CLI | `az` version ≥ 2.50 |
| Docker / Azure Container Registry | For building and hosting the Keycloak+JANUS image |
| Java 17 + Maven | For building the JANUS extension |
| PowerShell 7+ | For running `bootstrap/bootstrap.ps1` |

## Quick start

### 1. Clone and build the extension

```bash
git clone https://github.com/goldjg/janus.git
cd janus/janus
mvn clean package -DskipTests
```

### 2. Run one-time bootstrap

```powershell
# Fill in infra/parameters.bicepparam first
./bootstrap/bootstrap.ps1 `
  -TenantId "<your-tenant-id>" `
  -SubscriptionId "<your-subscription-id>" `
  -ResourceGroup "rg-janus-prod" `
  -Location "australiaeast"
```

The bootstrap script:
- Creates the resource group
- Assigns Microsoft Graph `Application.ReadWrite.OwnedBy` to the managed identity
- Configures the Keycloak realm

### 3. Deploy infrastructure

```bash
az deployment group create \
  --resource-group rg-janus-prod \
  --template-file infra/main.bicep \
  --parameters infra/parameters.bicepparam
```

### 4. Confirm DCR endpoint

```bash
curl https://<your-keycloak-fqdn>/realms/janus/clients-registrations/openid-connect \
  -X POST \
  -H "Content-Type: application/json" \
  -d @examples/mcp/dcr-request.json
```

## Registration policy

JANUS enforces a strict registration policy on every DCR request:

- Only `authorization_code` grant type is accepted
- `token_endpoint_auth_method` must be `none` (public client)
- Redirect URIs must match the configured allowlist (localhost loopback, native app schemes)
- Client name must be non-empty, ≤ 64 characters, matching a safe character set
- No client secret or certificate credentials are ever created
- Scopes are constrained to the configured gateway resource scope
- Oversized, malformed, duplicate, and unexpected metadata fields are rejected

See [docs/security-model.md](docs/security-model.md) and [docs/dcr-flow.md](docs/dcr-flow.md).

## Graph permissions

JANUS requires a single Graph application permission assigned to its Managed Identity:

| Permission | Type | Reason |
|---|---|---|
| `Application.ReadWrite.OwnedBy` | Application | Create and manage only the app registrations JANUS owns |

JANUS does **not** require:
- `Application.ReadWrite.All`
- `Directory.ReadWrite.All`
- Any user-delegated permissions

See [docs/entra-permissions.md](docs/entra-permissions.md).

## Lifecycle management

JANUS tags every Entra app registration it creates with:

```json
{
  "tags": ["janus-managed", "janus-realm:<realm>"],
  "notes": "Created by JANUS. Realm: <realm>. Created: <ISO8601>."
}
```

The cleanup job runs on a configurable schedule (default: daily) and removes stale registrations that exceed the configured retention period.

See [docs/lifecycle.md](docs/lifecycle.md).

## Documentation

| Document | Description |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Component architecture and trust boundaries |
| [docs/deployment.md](docs/deployment.md) | Step-by-step deployment guide |
| [docs/threat-model.md](docs/threat-model.md) | Threat model and mitigations |
| [docs/security-model.md](docs/security-model.md) | Security properties and guarantees |
| [docs/dcr-flow.md](docs/dcr-flow.md) | DCR sequence diagram and field mapping |
| [docs/entra-permissions.md](docs/entra-permissions.md) | Microsoft Graph permissions rationale |
| [docs/lifecycle.md](docs/lifecycle.md) | App registration lifecycle and cleanup |
| [docs/cimd-roadmap.md](docs/cimd-roadmap.md) | Client Identity Metadata Descriptor roadmap |

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## Security

See [SECURITY.md](SECURITY.md).

## License

[Apache 2.0](LICENSE)