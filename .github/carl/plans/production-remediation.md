# JANUS Production-Spec Remediation

## Plan metadata
- PR / branch: `main` working tree
- Status: completed
- Author: Codex team
- Created: 2026-08-29
- Last updated: 2026-08-30

## Task summary

Bring the initial JANUS prototype up to the user-provided production security,
deployment, lifecycle, testing and documentation specification.

## Current repository context

The initial implementation proves a partial DCR-to-Graph vertical slice but
contains aspirational documentation and lacks enforceable admission, complete
scope/tenant policy, conservative lifecycle evidence, production deployment,
OIDC bootstrap, and broad validation.

## Goal

Produce a small, auditable registration-plane service that provisions tightly
constrained Entra public clients while leaving authentication and gateway
authorization wholly with Entra and the gateway.

## Non-goals

- gateway token issuance, validation or proxying in JANUS;
- automatic gateway consent, assignments or group admission;
- speculative CIMD implementation;
- live tenant deployment during local implementation.

## Approved scope

See `.github/carl/current-pr-contract.md`.

## Forbidden scope

Live external mutation, real secrets, broad Graph grants and any architecture
that makes Keycloak/JANUS the gateway token issuer.

## Trust boundaries

See `.github/carl/trust-boundaries.md`.

## Invariants to preserve

- broker registration, never identity;
- registration is not authorization;
- fail closed on hostile DCR input;
- cleanup retains on uncertainty;
- managed identity at runtime and OIDC in CI/CD.

## Expected files / directories

`janus/**`, `infra/**`, `bootstrap/**`, `.github/workflows/**`, `docs/**`,
`tests/**`, root documentation, and `.github/carl/**`.

## Implementation phases

1. Reconcile governance and architecture decisions.
2. Separate DCR, provisioning, Graph and lifecycle responsibilities.
3. Enforce admission, tenant, gateway, scope, redirect and bounded-input policy.
4. Implement dry-run-first, evidence-aware, positively marked cleanup.
5. Complete OIDC bootstrap, modular Bicep and protected deployment workflow.
6. Add unit, mock, protocol, lifecycle, infrastructure and security validation.
7. Reconcile all operator and security documentation with verified behavior.

## Acceptance criteria

- No JANUS path accepts or emits a gateway bearer token.
- Unadmitted or invalid DCR input cannot create an Entra object.
- Graph creation is single-tenant, credential-free and restricted to configured
  gateway delegated scopes without granting consent or assignment.
- Cleanup defaults to dry-run and cannot delete on ambiguous evidence.
- CI deployment uses OIDC and immutable artifacts without Azure client secrets.
- Bootstrap is idempotent, `WhatIf`-safe and emits structured setup values.
- Local non-live validation passes, with unavailable checks reported.

## Contract assertions

1. Admission and policy rejection occur before Graph provisioning.
2. Provisioning payloads contain no credentials or unrelated permissions.
3. Cleanup requires marker, ownership, non-exclusion, age/activity eligibility,
   deletion bound and a final pre-delete recheck.
4. Pull requests cannot reach deployment credentials; production deployment
   uses protected OIDC federation.
5. Documentation labels platform-dependent or unverified behavior explicitly.

## Test strategy

- Unit/provider tests assert admission and normalized provisioning requests.
- Mock HTTP tests assert Graph payloads, pagination, throttling and safe errors.
- Lifecycle tests assert retain/delete reasons, dry-run and bounds.
- Static Bicep/PowerShell/workflow checks assert identity and deployment shape.
- Container smoke tests assert provider and cleanup entry points without live
  Graph access.
- Live tests remain opt-in and are the only proof of real MCP/Entra handoff.

## Stop conditions

See the active PR contract.

## Context reset requirements

Close the active contract and this plan after validation and reconciliation;
carry forward only durable invariants and documented limitations.

## Completion record

All seven implementation phases completed on 2026-08-30. The registration
adapter/core split, fail-closed policy and admission, managed-identity Graph
provisioning, conservative lifecycle engine, OIDC/bootstrap/IaC deployment
stack, test suite and operator/security documentation are implemented. Local
validation evidence and the remaining live-environment limitations are recorded
in `.github/carl/current-pr-contract.md`.
