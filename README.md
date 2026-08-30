# JANUS — MCP Client Registration Broker for Microsoft Entra ID

Microsoft Entra ID does not currently implement the Dynamic Client
Registration mechanism expected by some MCP clients. JANUS provides a narrow
compatibility bridge: it accepts a constrained legacy DCR request and creates a
real public-client application registration in Entra through Microsoft Graph.

> **Broker registration. Never broker identity.**

JANUS is not an identity provider, authorization server, token broker, token
cache, authentication proxy, session service, or custom OAuth implementation.
Microsoft Entra ID remains the issuer of the access token used at the protected
MCP gateway.

## Architecture

```text
                    Registration plane

MCP Client
    |
    | admitted, policy-constrained DCR
    v
  JANUS (Keycloak DCR adapter + provisioning core)
    |
    | managed identity / Microsoft Graph
    v
Entra public-client application registration

             Authentication and access plane
                       (JANUS absent)

MCP Client
    |
    | Authorization Code + PKCE
    v
Microsoft Entra ID
    |
    | Entra-issued access token
    v
MCP Gateway
    |
    | issuer + tenant + audience + signature + lifetime
    | + assignment + group + scope/app-role admission
    v
MCP Server / Tools
```

Successful registration creates an OAuth client identity. It does not grant
consent, assignment, group membership, a gateway role, or gateway access.

## Security properties

- Generated applications are single-tenant public clients.
- No client password or certificate is created.
- Only exact policy-approved redirect URIs and gateway delegated scopes are
  accepted.
- DCR uses explicit admission and bounded creation controls; anonymous
  unlimited object creation is unsupported.
- Runtime Graph access uses user-assigned managed identity and
  `Application.ReadWrite.OwnedBy`.
- GitHub deployment uses OIDC workload identity federation, not a client secret.
- Cleanup is dry-run-first, positively identifies JANUS-managed objects and
  retains them when activity evidence is uncertain.
- Tokens, authorization codes, credentials and full JWTs are never logged.

See [the security model](docs/security-model.md) and
[threat model](docs/threat-model.md).

## Repository layout

| Path | Purpose |
|---|---|
| `janus/` | Java 17 Keycloak SPI, registration policy, provisioning/Graph integration and cleanup entry point |
| `infra/` | Modular Azure Bicep for Container Apps, identity, registry, database and diagnostics |
| `bootstrap/` | Idempotent PowerShell 7 tenant/subscription bootstrap with `-WhatIf` support |
| `.github/workflows/` | Pull-request validation and protected OIDC deployment |
| `docs/` | Architecture, decisions, deployment, security, lifecycle and operations |
| `examples/mcp/` | Safe example DCR requests and client notes |
| `tests/` | Cross-component, integration and security test guidance |

## Prerequisites

- Java 17 and Maven 3.9 for local extension work
- PowerShell 7 and Azure CLI for bootstrap/deployment validation
- Docker or Podman for container builds
- An Azure subscription and commercial Microsoft Entra tenant
- A predesigned MCP gateway Entra application exposing an approved delegated
  scope and enforcing assignment/group admission
- A GitHub repository/environment able to use Entra workload identity federation

Sovereign-cloud endpoints and feature availability must be validated before
deployment; the initial defaults assume Azure commercial cloud.

## Build and test

```bash
cd janus
mvn -B clean verify
```

Build the Bicep template without deploying:

```bash
az bicep build --file infra/main.bicep
```

Build the container for the current host:

```bash
docker build -t janus:local janus
```

On the supported Raspberry Pi development host this produces an ARM64 image;
the GitHub workflow produces the production AMD64 image.

Live Entra/Graph tests are opt-in, require a dedicated test tenant and must
never run automatically for public pull requests.

## Deployment outline

1. Review [the architecture decisions](docs/adr/0001-registration-plane-and-issuer-handoff.md), [permissions](docs/entra-permissions.md), and [threat model](docs/threat-model.md).
2. Run the bootstrap script first with `-WhatIf`, then deliberately apply the
   reviewed gateway, workload-identity, RBAC and Graph prerequisites.
3. Configure the non-secret repository/environment values printed by bootstrap.
4. Protect the GitHub `production` Environment with required reviewers.
5. Run pull-request validation, then invoke the deployment workflow.
6. Perform the documented smoke tests and opt-in issuer-handoff test before
   exposing DCR to intended clients.
7. Review cleanup in dry-run before enabling deletion.

Detailed commands and configuration are in
[docs/deployment.md](docs/deployment.md). Operational response and removal are
in [docs/operations.md](docs/operations.md).

## DCR compatibility and CIMD

MCP specification `2026-07-28` formally deprecated DCR in favour of Client ID
Metadata Documents while retaining DCR for backward compatibility. JANUS keeps
DCR as a thin adapter over a protocol-neutral provisioning core. It does not
implement speculative CIMD behavior.

See [the CIMD migration roadmap](docs/cimd-roadmap.md).

## Evidence boundary

Local tests can prove parsing, policy, Graph payload, lifecycle and deployment
contracts. They cannot prove that a particular MCP client follows the Entra
issuer handoff, that Conditional Access evaluates as intended, or that a
gateway enforces admission correctly. Those claims require opt-in end-to-end
tests in the operator's tenant.

## Contributing and security

See [CONTRIBUTING.md](CONTRIBUTING.md) for development guidance. Report
vulnerabilities privately as described in [SECURITY.md](SECURITY.md).

Licensed under the [Apache License 2.0](LICENSE).
