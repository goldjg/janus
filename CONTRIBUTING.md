# Contributing to JANUS

JANUS is security-sensitive identity infrastructure. Small, auditable changes
with direct contract tests are preferred over broad abstraction or speculative
features.

## Before changing code

1. Read `AGENTS.md` and hydrate the active cARL contract and invariants.
2. Read the registration-plane and lifecycle ADRs under `docs/adr/`.
3. State the trust boundary, permissions and failure behavior affected.
4. For non-trivial work, add contract assertions before implementation.

Never weaken the rule: **Broker registration. Never broker identity.**

## Local development

Requirements:

- Java 17
- Maven 3.9+
- Docker or Podman for image checks
- PowerShell 7 and Azure CLI for bootstrap/IaC checks

Build and test:

```bash
cd janus
mvn -B clean verify
```

Validate Bicep without deploying:

```bash
az bicep build --file infra/main.bicep
```

Parse the bootstrap script without running it:

```powershell
$tokens = $null
$errors = $null
[System.Management.Automation.Language.Parser]::ParseFile(
  (Resolve-Path bootstrap/bootstrap.ps1),
  [ref]$tokens,
  [ref]$errors
) | Out-Null
if ($errors.Count -gt 0) { $errors | Format-List; exit 1 }
```

## Testing rules

- Unit tests must not access a live tenant or Container Apps identity endpoint.
- Mock Graph at the HTTP boundary and assert exact payloads, retries,
  pagination and sanitized failures.
- Security policy tests should cover boundary values and rejected hostile input.
- Lifecycle tests begin in dry-run and retain on ambiguous evidence.
- Live tests must use `@Tag("live-integration")`, be opt-in, use a dedicated
  tenant and maintain a teardown inventory.
- Pull requests must never receive Azure credentials.

Reference-gateway claim tests may validate policy after a mature framework has
verified JWT signature and algorithms. Do not implement JWT parsing or crypto
inside JANUS merely to satisfy tests.

## Dependency changes

Explain necessity, alternatives, transitive impact, licence, maintenance and
security posture. Pin versions explicitly and avoid unrelated `pom.xml` churn.
Container base changes require image scanning or a documented unavailable check.

## Documentation expectations

Update durable documentation when behavior, configuration, permissions, trust
boundaries, failure semantics or operational steps change. Clearly label:

- implemented and locally tested behavior;
- opt-in live evidence;
- platform/licence-dependent behavior;
- roadmap-only behavior.

Do not describe a planned control as implemented.

## Pull requests

Include:

- goal and non-goals;
- files/trust boundaries affected;
- Graph/Entra/Azure permissions changed or explicitly avoided;
- contract assertions and tests run;
- live tests not run;
- deployment/rollback impact;
- cARL/docs update decision;
- residual risk.

Report vulnerabilities privately through the process in `SECURITY.md`.
