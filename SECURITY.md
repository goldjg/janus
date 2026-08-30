# Security policy

## Supported versions

JANUS has not yet published a stable release. Until a release policy is
declared, security fixes target the current default branch only. Do not infer
support from the Maven snapshot version.

## Primary invariant

> JANUS never issues, proxies, caches, exchanges, re-signs, inspects for reuse,
> or stores the bearer token used to access the protected MCP gateway.

Microsoft Entra ID is the gateway token issuer. JANUS operates only on client
registration. Any report showing a gateway token, authorization code or refresh
token entering JANUS is security-critical.

## Reporting a vulnerability

Do not open a public issue containing exploit details, tenant identifiers,
tokens, credentials or customer data.

Use GitHub's private vulnerability reporting for this repository. Include:

- affected commit/version;
- impacted component and trust boundary;
- reproducible steps using synthetic data where possible;
- expected and observed behavior;
- security impact and suggested containment;
- whether any live tenant objects were created or changed.

If private reporting is unavailable, contact the repository owner through the
maintainer channel listed on the GitHub profile and request a secure reporting
path before sending details.

## In-scope security issues

- bypass of admission, request bounds, redirect, tenant or gateway-scope policy;
- creation of credentials or unrelated permissions on generated clients;
- automatic consent/assignment or registration-to-authorization confusion;
- Graph origin/SSRF, token disclosure or excessive permission use;
- cleanup of an unmarked, excluded, active or uncertain application;
- registration storms that bypass documented creation controls;
- Keycloak/JANUS becoming the gateway token issuer or proxy;
- CI workload-identity subject/audience mistakes or secret-based cloud access;
- bootstrap tenant/subscription confusion or unsafe `WhatIf` behavior;
- log injection or sensitive token/credential logging;
- vulnerable dependencies or container supply-chain compromise;
- documentation that directs operators to materially unsafe production defaults.

Platform vulnerabilities in Microsoft Entra, Graph, Azure, Keycloak,
agentgateway or an independently maintained MCP gateway should also be reported
to the relevant upstream security team. JANUS integration/configuration flaws
remain in scope here.

## Safe research expectations

- Use a dedicated tenant/subscription you are authorized to test.
- Keep live tests opt-in and inventory every created object.
- Do not test denial of service or registration exhaustion against shared/public
  infrastructure.
- Never include real tokens or credentials in fixtures, issues or logs.
- Start lifecycle testing in dry-run and use synthetic marked applications.
- Do not broaden Graph permissions to demonstrate an issue.

## Security architecture and operations

- [Security model](docs/security-model.md)
- [Threat model](docs/threat-model.md)
- [Trust boundaries](.github/carl/trust-boundaries.md)
- [Graph permissions](docs/entra-permissions.md)
- [Operations and recovery](docs/operations.md)
- [Registration-plane ADR](docs/adr/0001-registration-plane-and-issuer-handoff.md)
- [Lifecycle ADR](docs/adr/0002-conservative-lifecycle-evidence.md)

## Dependency and release posture

Production releases should publish immutable container digests, dependency and
container scan results, test evidence, upgrade notes and known residual risks.
Critical or High vulnerabilities require remediation or an explicit documented
mitigation before release; silently accepting them is not supported.

The repository's scoped Trivy exception for `CVE-2025-59250` applies only to
Keycloak's bundled `mssql-jdbc-13.2.1.jre11.jar`. Microsoft identifies 13.2.1
as the release containing the fix, while Trivy 0.73.0 compares the embedded
unqualified Maven version (`13.2.1`) with the classifier-qualified fixed
version (`13.2.1.jre11`). The exception is path- and package-scoped, records its
rationale, and expires on 2026-11-30. It must be revalidated on any Keycloak,
Trivy or vulnerability-database update; it does not waive any other finding.
