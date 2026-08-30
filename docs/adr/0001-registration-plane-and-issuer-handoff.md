# ADR 0001: Registration plane and issuer handoff

- Status: accepted
- Date: 2026-08-29

## Context

Legacy MCP clients may expect RFC 7591 Dynamic Client Registration, while
Microsoft Entra ID does not expose that registration protocol. Using Keycloak
as the gateway authorization server would weaken the intended Entra policy and
move JANUS into the identity plane.

## Decision

JANUS uses Keycloak only as a compatibility adapter for registration. A DCR
request is admitted, normalized and passed to a protocol-neutral provisioning
core. The core creates a real, single-tenant Entra public-client application
through Microsoft Graph and returns its Entra application ID.

The client must then use the explicitly configured Entra issuer for
authorization code plus PKCE. The protected gateway accepts only Entra-issued
tokens and independently enforces issuer, tenant, audience, signature,
lifetime, subject type, assignment, group and scope/app-role policy.

JANUS never grants consent, assigns users/groups, validates gateway JWTs or
handles gateway authorization codes, refresh tokens or access tokens.

## Consequences

- Successful registration creates identity, not gateway admission.
- Client compatibility requires an explicit and tested issuer handoff; a
  Keycloak DCR response alone does not prove that a client will authenticate
  against Entra.
- DCR-specific fields stay outside the Graph transport.
- A future CIMD adapter can reuse the same provisioning and lifecycle core.
- End-to-end client/Entra behavior remains unproven until opt-in live tests pass.

## Rejected alternatives

- Keycloak as gateway token issuer: violates the primary security invariant.
- Token exchange or proxying through JANUS: makes JANUS an identity broker.
- Automatically granting gateway consent or assignments: conflates registration
  with authorization.
- Custom OAuth/JWT implementation: unnecessary and unsafe.
