<!-- version: 3.0.0 -->
# Durable Architectural Truth Cache

## Project purpose

JANUS is an MCP client registration broker for Microsoft Entra ID. It adapts
legacy MCP Dynamic Client Registration requests into real Entra public-client
application registrations. Microsoft Entra ID remains the OAuth/OIDC
authorization server and the sole issuer of access tokens accepted by the MCP
gateway.

The governing phrase is: **Broker registration. Never broker identity.**

## Durable non-goals

JANUS is not an identity provider, authorization server, token broker, token
cache, session service, reverse proxy for user authentication, or custom OAuth
implementation. Keycloak may provide the legacy DCR protocol surface, but its
tokens must never be accepted by the protected MCP gateway.

## Architecture

- `janus/` contains a Java 17 Keycloak SPI adapter, a protocol-neutral
  registration/provisioning core, Microsoft Graph transport, admission and
  registration policy, and lifecycle cleanup entry point.
- `infra/` contains modular Azure Bicep for the deployment boundary.
- `bootstrap/` contains the one-time PowerShell 7 tenant/subscription bootstrap.
- `.github/workflows/` validates and deploys with GitHub OIDC; long-lived Azure
  client secrets are forbidden.
- `docs/` contains architecture decisions, trust/security models, deployment,
  lifecycle, operational and CIMD migration guidance.

The runtime path is:

1. an admitted MCP client submits bounded DCR metadata;
2. the DCR adapter validates redirect, grant, response, auth-method, tenant and
   gateway-scope policy;
3. the provisioning core creates a single-tenant Entra public application via
   Microsoft Graph using user-assigned managed identity;
4. JANUS returns the Entra client ID;
5. the client authenticates directly with Entra using authorization code and
   PKCE;
6. the gateway independently validates and authorizes the Entra-issued token.

Registration success never establishes gateway authorization.

## Security invariants

- No JANUS component issues, proxies, caches, exchanges, re-signs, or stores a
  bearer token used to access the MCP gateway.
- Generated clients have no password or certificate credentials and are
  single-tenant public clients.
- Runtime Graph access uses managed identity with
  `Application.ReadWrite.OwnedBy`; broad Graph write permissions are forbidden.
- Container Apps managed-identity tokens come only from the platform-injected
  local identity sidecar with its rotating identity header and explicit UAMI client ID.
- DCR is hostile input and externally reachable registration requires explicit
  admission plus bounded creation controls.
- Redirects, scopes and tenant binding fail closed.
- Cleanup deletes only positively marked JANUS-owned applications, defaults to
  dry-run, and retains on missing or ambiguous activity evidence.
- Secrets, authorization codes, bearer tokens and JWT contents are never logged.
- GitHub deployment uses workload identity federation, not an Azure client
  secret.

## Protocol direction

MCP specification 2026-07-28 formally deprecates DCR in favour of Client ID
Metadata Documents (CIMD), while retaining DCR temporarily for compatibility.
DCR therefore remains an adapter over a protocol-neutral provisioning core.
CIMD work stays roadmap-only until client behavior and standards are mature
enough to implement without speculation.

## Lifecycle truth

Entra application `createdDateTime` is creation evidence, not use evidence.
Sign-in/audit data may provide a proxy for authentication or token issuance,
but availability depends on licensing, retention and reporting latency and does
not prove gateway access. Cleanup must make that uncertainty explicit. When
reliable use evidence is absent, retain the registration unless an operator
deploys a separately reviewed evidence provider with complete reporting-window coverage.

## Validation

- Java build and tests: `cd janus && mvn -B clean verify`
- Bicep: `az bicep build --file infra/main.bicep`
- PowerShell syntax/tests: PowerShell 7 parser and Pester where available
- Container: build on supported architecture and run non-live smoke checks
- Live Entra tests are opt-in and must never run for untrusted pull requests.

## Known limitations

- End-to-end behavior with specific MCP clients and a real Entra tenant requires
  opt-in integration evidence; unit tests cannot prove issuer handoff.
- Sign-in-log-based lifecycle evidence is tenant-licence and retention dependent.
- DCR is a compatibility surface with a planned CIMD migration path.
- Registration rate/idempotency counters are process-local, so the supplied
  deployment stays single-replica until a distributed edge/race strategy is reviewed.

## Last updated

2026-08-30 during production-spec remediation.
