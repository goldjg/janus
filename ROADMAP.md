# JANUS roadmap

JANUS is deliberately narrow: registration translation with Microsoft Entra ID
preserved as the identity authority. Roadmap work must not move gateway bearer
tokens through JANUS.

## Initial production baseline

- constrained RFC 7591 adapter over a protocol-neutral provisioning core
- managed-identity Graph creation of single-tenant, credential-free public clients
- exact redirect and gateway-scope policy plus bounded initial-access-token admission
- dry-run-first, evidence-aware lifecycle job with pre-delete rechecks
- modular Azure Container Apps/PostgreSQL/observability Bicep
- PowerShell tenant bootstrap and GitHub OIDC deployment
- security, threat, deployment, lifecycle, operations, and CIMD documentation

## Operator validation before rollout

- exercise the DCR-to-Entra issuer handoff in a dedicated tenant
- prove the target MCP client uses Authorization Code with PKCE against Entra
- prove Conditional Access and the gateway's assignment/group/scope policy deny
  users independently of registration success
- review workload sizing, Azure regional availability, database recovery, edge
  admission/rate limiting, and tenant application-object quotas
- scan and approve the immutable production image

## Lifecycle evidence

The initial cleanup policy retains when Entra sign-in evidence is unavailable.
Before enabling deletion, design and independently review an observer that can
prove reporting-window coverage, write unambiguous evidence markers, and
document its `AuditLog.Read.All`/licensing/retention impact. Prefer a separate
read-only reporting identity; do not broaden the provisioning identity silently.

## CIMD evolution

Track MCP Client ID Metadata Documents and client adoption. Add a CIMD adapter
only when interoperability requirements are stable enough to test without
weakening redirect ownership or turning JANUS into an authorization server.
See [docs/cimd-roadmap.md](docs/cimd-roadmap.md).

## Optional gateway integration

Agentgateway may be evaluated as a protected-resource adapter when configured
to validate Entra-issued tokens. Its Keycloak-as-issuer flow is outside JANUS's
security invariant and must not be adopted for gateway access.
