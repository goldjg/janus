# Architecture

## Purpose

JANUS translates an admitted legacy MCP Dynamic Client Registration request
into a constrained Microsoft Entra public-client application registration. It
then exits the flow. Microsoft Entra ID performs authentication and issues the
gateway token; the gateway performs authorization.

```text
Registration plane

MCP client -- DCR --> JANUS/Keycloak adapter --> policy + provisioning core
                                                       |
                                                       | managed identity
                                                       v
                                                Microsoft Graph
                                                       |
                                                       v
                                             Entra app registration

Authentication and access plane (JANUS absent)

MCP client -- authorization code + PKCE --> Microsoft Entra ID
MCP client <-- Entra access token ---------- Microsoft Entra ID
MCP client -- Entra access token ----------> MCP gateway --> MCP server/tools
                                              issuer, tenant, audience,
                                              signature, time, assignment,
                                              group and scope/role checks
```

## Component boundaries

### DCR adapter

The Keycloak SPI owns RFC 7591 parsing, request-size enforcement, admission,
protocol error mapping and response serialization. It converts only accepted
metadata into a normalized provisioning request.

Client registration is an internal Keycloak SPI. The image therefore pins a
tested Keycloak version and digest; every Keycloak upgrade requires compilation,
provider-discovery, protocol, and container smoke tests. This maintenance
coupling stays inside the legacy DCR adapter rather than the provisioning core.

Keycloak is not the gateway issuer. A client must be configured or discovered
to use the Entra issuer after registration. This handoff requires explicit
per-client integration evidence; registering through a Keycloak URL does not by
itself prove the client will authorize against Entra.

### Registration policy

Policy binds a request to one configured tenant and gateway resource. It
accepts only public authorization-code clients, explicitly approved redirect
URIs and gateway delegated scopes. It rejects unknown, oversized, duplicate or
unsupported metadata before any Graph call.

Admission and creation limits protect the tenant object quota. Edge rate
limiting or IP restriction complements but does not replace application-level
admission.

### Provisioning core

The core accepts a protocol-neutral, already normalized request. Its interface
is the future attachment point for a CIMD adapter. It returns the Entra client
ID and safe operational metadata; it does not return a client credential or
grant gateway access.

### Microsoft Graph transport

The transport uses the Container App's user-assigned managed identity and a
fixed Microsoft Graph origin. It creates single-tenant applications with
public-client redirect URIs, approved gateway `requiredResourceAccess`, and
explicit JANUS ownership/lifecycle markers. It never creates password or key
credentials and never grants consent or assignments.

The runtime permission is `Application.ReadWrite.OwnedBy`. Microsoft documents
that this permission can create and fully manage applications the caller owns,
while also allowing tenant-wide listing of applications and service principals.
JANUS must therefore continue to filter and positively identify its own
objects; the permission is not a substitute for application-level checks.

### Lifecycle cleanup

The scheduled Container Apps Job shares the Graph transport but has a separate
decision policy. It is dry-run-first, positively identifies ownership, honors
manual exclusions, bounds deletions and retains on uncertain activity evidence.
See `docs/lifecycle.md` and ADR 0002.

### Gateway boundary

The gateway is intentionally not implemented by JANUS. It validates mature
framework output rather than using bespoke crypto, and independently enforces:

- exact Entra issuer and permitted tenant;
- gateway audience;
- signature and key lifecycle;
- `nbf`, `exp` and acceptable clock skew;
- expected user or workload subject type;
- required assignment and groups, including group overage handling;
- required delegated scope or app role.

`iat` is informational and policy-relevant but is not replay prevention.

## Azure deployment

The deployment uses Azure Container Apps, a user-assigned managed identity,
Azure Container Registry, Log Analytics and a scheduled Container Apps Job.
Production Keycloak state uses an external persistent database rather than
container-local development storage.

GitHub Actions authenticates with an Entra federated credential and deploys an
immutable image through a protected GitHub Environment. Pull-request validation
has no Azure credentials.

Public DCR ingress requires explicit admission. Operators should also restrict
source CIDRs or place a rate-limiting edge such as Azure Front Door WAF in front
of the Container App. Container replica scaling is not rate limiting.

## Optional agentgateway integration

Agentgateway can act as an MCP resource server, publish protected-resource
metadata, validate JWTs and apply route authorization/rate policy. Its current
native Entra provider is architecturally relevant because it keeps Entra as the
issuer, but it expects a configured client ID and does not by itself replace
JANUS's dynamic Entra provisioning contract.

Agentgateway's Keycloak provider is not suitable for the JANUS access plane: it
uses Keycloak as the authorization server and validates Keycloak-issued tokens,
which violates JANUS's issuer invariant. A future integration must use the
native Entra validation path and isolate any JANUS DCR routing as registration
only. Agentgateway is therefore optional and not part of the initial runtime.

Reference: [agentgateway MCP authentication](https://agentgateway.dev/docs/standalone/latest/configuration/security/mcp-authn/).

## State and identifiers

- Entra application object ID: Graph lifecycle target; never exposed as a
  credential.
- Entra application/client ID: returned by DCR and used by the MCP client.
- Display name: operational aid only; never ownership or deletion evidence.
- Tags/notes: explicit JANUS marker, environment/realm marker, lifecycle schema
  and optional cleanup exclusion.
- Correlation ID: joins sanitized registration and Graph outcomes.

JANUS does not maintain an application database merely to appear stateful.
State required for admission/idempotency must have explicit consistency and
replica semantics.

## Failure behavior

- Invalid or unadmitted requests fail before Graph.
- Missing critical configuration fails closed.
- Graph failures return a correlation reference without upstream bodies/tokens.
- Ambiguous cleanup evidence retains the application.
- A JANUS outage blocks new registration but does not interrupt existing Entra
  authentication or gateway authorization.

## Architecture decisions

- [ADR 0001: registration plane and issuer handoff](adr/0001-registration-plane-and-issuer-handoff.md)
- [ADR 0002: conservative lifecycle evidence](adr/0002-conservative-lifecycle-evidence.md)
