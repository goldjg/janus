<!-- version: 1.1.0 -->
# Current PR Contract

## Goal
Bring JANUS from its initial prototype to a production-oriented MCP client
registration broker that satisfies the user-provided specification while
preserving Microsoft Entra ID as the sole gateway token issuer.

## Contract status
closed — completed 2026-08-30

## Non-goals
- Do not make JANUS an identity provider, authorization server, token broker,
  token cache, session service, or authentication reverse proxy.
- Do not make Keycloak the issuer of tokens accepted by the MCP gateway.
- Do not implement speculative CIMD protocol behavior.
- Do not deploy to, mutate, or delete resources in a live Azure/Entra tenant.

## Carry-forward rules
The registration-plane-only security invariant and least-privilege boundaries
are durable. Task-specific file lists and implementation sequencing close with
this contract.

## Approved scope
- Refactor and extend the Java registration provider, Graph integration,
  configuration, admission controls, lifecycle cleanup, and related tests.
- Add supporting interfaces and test fixtures needed to separate protocol,
  provisioning, admission, and lifecycle concerns.
- Update Bicep, PowerShell bootstrap, container packaging, GitHub Actions, and
  repository documentation to implement and accurately describe the system.
- Reconcile cARL durable memory, invariants, trust boundaries, pack selection,
  and repository planning artefacts with JANUS.
- Run local build, unit, static, IaC, PowerShell, and container checks without
  accessing or mutating a live tenant.

## Intentional amendments
The stale cARL memory and pack selection inherited from the unrelated cARL Go
repository may be replaced with JANUS-specific durable truth. This amendment
does not weaken any baseline security invariant.

## Forbidden scope
- Live Azure, Entra, Microsoft Graph, GitHub, registry, or production changes.
- Real credentials, tokens, tenant data, or secret-bearing fixtures.
- Broad permissions such as Application.ReadWrite.All or long-lived GitHub
  deployment credentials.
- Gateway token issuance, exchange, proxying, caching, or custom JWT crypto.

## Architectural constraints
- Broker registration; never broker identity.
- Keep DCR as an adapter over a protocol-neutral provisioning core.
- Generated Entra clients are single-tenant public clients with no credentials.
- Registration never implies gateway authorization.
- Cleanup is dry-run-first and retains applications when evidence is uncertain.
- Prefer small, auditable, standard-library-oriented components.

## Security constraints
- Fail closed on missing or malformed tenant, gateway, scope, redirect,
  admission, and lifecycle configuration.
- Bound hostile input and creation rates.
- Require a defensible admission mechanism for externally reachable DCR.
- Use managed identity for runtime Graph access and OIDC for GitHub deployment.
- Never log bearer tokens, authorization codes, refresh tokens, secrets, or JWTs.
- Delete only positively identified JANUS-managed applications.

## Files expected to change
- `janus/**`
- `infra/**`
- `bootstrap/**`
- `.github/workflows/**`
- `tests/**`
- `docs/**`
- `examples/**`
- root documentation and build metadata
- `.github/carl/**`

## Tests / validation
- `mvn -B clean verify` from `janus/`
- focused Java unit and mocked HTTP tests
- Bicep build/lint when Azure CLI/Bicep is available
- PowerShell parser/Pester checks when PowerShell is available
- GitHub Actions static inspection/lint when available
- Docker image build and container smoke checks when feasible on ARM64
- no live integration tests without explicit tenant credentials and approval

## Stop conditions
- Any design would put JANUS in the gateway token path.
- Required behavior needs broad Graph permissions or stored runtime secrets.
- Validation would require unapproved live tenant or destructive operations.
- Existing user changes overlap materially with the same files unexpectedly.

## Escalation triggers
- Live Azure/Entra/GitHub mutations or tenant credentials are required.
- Docker validation requires privileged host access.
- A platform limitation prevents the Entra-issued-token architecture.
- A compatibility exception would weaken redirect, scope, tenant, or admission
  controls.

## Context reset notes
Close this contract only after implementation, validation, documentation and
cARL reconciliation are complete, or explicitly mark unresolved work.

## Closure evidence

- Maven `clean verify`: 116 tests, zero failures, errors or skips.
- Maven dependency analysis completed with no dependency problems.
- Bicep compilation, PowerShell parsing/static checks, actionlint, YAML/JSON
  parsing, local-link validation, secret-pattern scanning and `git diff
  --check` passed.
- The final ARM64 image built from the working tree, retained the provider,
  realm and cleanup artifacts, reported Keycloak 26.7.2 with PostgreSQL in
  optimized mode, and failed closed when the cleanup entry point lacked its
  required realm configuration.
- Trivy 0.73.0 reported no unmitigated fixable High or Critical finding. Its
  sole report was the documented classifier-normalization false positive for
  Microsoft's fixed `mssql-jdbc 13.2.1.jre11`; the exact-path/package exception
  expires on 2026-11-30.

## Residual limitations

- No live Azure, Entra, Graph, GitHub, registry, MCP-client or gateway test was
  run under this contract.
- The lifecycle observer is deliberately not fabricated: absent fresh trusted
  usage evidence, cleanup retains registrations. Deployments therefore need a
  separately reviewed sign-in evidence ingestion decision before automatic
  inactivity deletion can be enabled meaningfully.
- JANUS integrates with Keycloak's internal client-registration SPI, so a
  Keycloak upgrade requires the protocol and container smoke suites.
