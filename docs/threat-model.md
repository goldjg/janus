# JANUS threat model

## Scope and assets

JANUS protects the integrity and availability of the Entra application
registration plane. It does not protect or process MCP gateway bearer tokens.

Assets include:

- the runtime managed identity and its Graph authority;
- tenant application-object quota;
- approved redirect and gateway-scope policy;
- generated application ownership/lifecycle metadata;
- GitHub workload identity and Azure deployment authority;
- Keycloak administrative state and the JANUS deployment;
- audit evidence required to investigate registration and deletion decisions.

## Trust boundaries

1. Internet MCP client to public/edge DCR admission.
2. Admitted metadata to policy normalization.
3. Provisioning core to managed-identity Microsoft Graph calls.
4. JANUS response to an untrusted public OAuth client.
5. Client to Entra authorization and token endpoints; JANUS is absent.
6. Entra token to the independently operated MCP gateway; JANUS is absent.
7. Lifecycle observation to destructive Graph deletion.
8. GitHub-hosted automation to Azure through workload identity federation.
9. Tenant administrator workstation to one-time bootstrap operations.

The canonical crossing controls are in
`.github/carl/trust-boundaries.md`.

## Threat analysis

| Threat | Impact | Required control | Residual risk / evidence |
|---|---|---|---|
| Malicious or unauthenticated MCP client | Tenant object exhaustion | Explicit admission, bounded request and creation limits, fail-closed configuration | Shared admission credentials can be redistributed; edge rate limits remain recommended |
| Registration storm | Graph throttling, cost, quota exhaustion | Per-source and global bounds, idempotency/duplicate handling, Retry-After-aware bounded retry, circuit-open failure | In-process limits are per replica unless backed by shared admission/edge controls |
| Malicious or oversized metadata | Memory/CPU exhaustion, parser abuse | Closed JSON schema, total body limit, bounded strings/arrays, reject unsupported fields | Reverse-proxy limits provide additional defence |
| Redirect URI poisoning/open redirect | Authorization-code theft | Exact structured URI rules, no fragments/userinfo/wildcards, loopback host and numeric port validation, explicit custom schemes | Operator-approved custom schemes can still be claimed by another local application |
| PKCE downgrade | Code interception | Public clients only; DCR advertises authorization code; clients and Entra must use S256 PKCE | App registration alone cannot prove a client used PKCE; live tests and gateway/CA policy are required |
| Unapproved scopes or APIs | Excess client capability | Map only configured gateway delegated scope IDs; no unrelated Graph access; no consent grant | Declared required access is not authorization; tenant consent policy still applies |
| Authentication treated as authorization | Unauthorized gateway access | Gateway requires assignment and independently checks tenant, issuer, audience, signature, lifetime, groups and scopes/roles | Gateway is a separate system and must be tested separately |
| Tenant/issuer/audience confusion | Cross-tenant or wrong-resource token acceptance | Bind registration to configured tenant/resource; gateway validates exact values and RFC 9207 issuer response | JANUS cannot compensate for a misconfigured gateway |
| JWT algorithm/claim confusion | Token forgery or admission bypass | Mature gateway middleware and pinned algorithms/keys; no JWT validation in JANUS | Reference claim tests do not replace cryptographic integration tests |
| Token replay or misleading `iat` | Repeated unauthorized calls | Gateway lifetime and nonce/DPoP/resource controls where required; never use `iat` alone as replay protection | Bearer tokens remain replayable within validity absent sender constraint |
| Missing/groups overage claims | Incorrect group admission | Deny or use a bounded, cached, revocation-aware Graph resolution strategy at the gateway | JANUS does not resolve groups or authorize gateway requests |
| User/app subject confusion | Workload token admitted as user or inverse | Gateway explicitly distinguishes delegated/user and app-only claim shapes | Claim conventions must match the gateway framework and tenant |
| Managed identity compromise | Creation/deletion of owned apps, tenant-wide app enumeration | `Application.ReadWrite.OwnedBy`, isolated workload, restricted ingress, no shell/debug exposure, alerting | The permission can list all tenant apps/service principals and manage owned objects |
| Graph permission escalation | Tenant-wide compromise | Never grant `.All` write or permission-grant roles to runtime identity; bootstrap displays consent target | Tenant admins can still misconfigure authority outside JANUS |
| Container/Keycloak compromise | Runtime Graph-token theft and object manipulation | Non-root image, patched/pinned base, minimal provider, network/ingress restrictions, immutable deploy, diagnostics | Managed identity remains available to compromised runtime code |
| SSRF | Managed-identity token theft | No user-controlled outbound URL; Container Apps identity endpoint is required to be local HTTP and Graph is fixed HTTPS; no redirect following | A compromised process can still call its own identity sidecar |
| Sensitive logging/log injection | Credential disclosure or audit corruption | Structured fields, sanitized values, no tokens/codes/JWTs/secrets, bounded correlation IDs | Platform request logs must be reviewed independently |
| Stale or abandoned registrations | Quota growth and attack surface | Explicit markers, evidence-aware cleanup, dry-run, bounded deletes | Reliable use evidence may be unavailable; retention is safer than guessing |
| Cleanup false positive | Active client deletion | Positive marker and ownership checks, exclusion marker, activity evidence, final recheck, retain on uncertainty | Deletion disrupts clients and a recreated app receives a different ID |
| Cleanup race | Use begins between list and delete | Re-read/re-evaluate immediately before delete; bound run duration | Sign-in reporting latency cannot eliminate every race |
| Deletion/recreation identifier assumptions | Incorrect authorization restoration | Never treat display names as identity; never assume app/client IDs are reused | Manual recovery may require a new client registration and assignments |
| Sign-in evidence ambiguity | Incorrect lifecycle decision | Record source/time/confidence; account for licence, retention and latency; distinguish auth from gateway use | No universal authoritative gateway-use timestamp exists in Entra app objects |
| Graph throttling/outage | Registration/cleanup unavailability | Timeouts, bounded exponential backoff with jitter/Retry-After, no partial create retry without idempotency | Operator retry may create duplicates without durable idempotency evidence |
| Supply-chain compromise | Arbitrary CI/runtime execution | SHA-pin actions, pin/scan container and Maven inputs, protected environment, minimal token permissions | Upstream artifacts still require ongoing vulnerability management |
| Pull-request workflow compromise | Secret or cloud compromise | PR validation has read-only permissions and no Azure environment/credentials | Maintainer workflow changes require review/branch protection |
| Deployment identity compromise | Azure resource compromise | GitHub OIDC subject restriction, audience/issuer checks, least RBAC, protected environment | Repository/environment administrators remain privileged |
| Bootstrap workstation compromise | Tenant-wide administrative misuse | Explicit tenant/subscription checks, `WhatIf`, `ShouldProcess`, no secret output, short interactive admin session | Bootstrap necessarily crosses a tenant-admin boundary |
| Keycloak becomes gateway issuer | Loss of Entra CA/session/risk enforcement | Gateway accepts only Entra issuer/audience; no JANUS token path | Must be proven by gateway integration tests, not documentation alone |
| Future CIMD adapter drift | New registration bypass | Reuse the same normalized provisioning/admission core and threat review | CIMD remains roadmap-only until implemented and tested |

## Abuse-control deployment choices

JANUS admission is necessary even when edge controls are present. For a public
endpoint, place a rate-limiting edge such as Azure Front Door WAF in front of
the Container App, or restrict Container Apps ingress to known source CIDRs.
Container Apps replica scaling is capacity control, not request rate limiting.

If client compatibility prevents a static admission credential, operators must
choose and document another bounded bootstrap channel. An anonymously reachable
unlimited object-creation endpoint is not a supported production mode.

## Lifecycle evidence

`createdDateTime` proves age only. Entra sign-in logs can be queried through
Microsoft Graph but have licensing, permission, retention and latency
constraints. A sign-in proves authentication/token activity, not successful MCP
gateway access. No synthetic `lastUsedAt` value may be presented as authoritative.

## Out of scope

- vulnerabilities in Entra, Graph, Azure, Keycloak, MCP clients or the gateway
  that are not caused by JANUS configuration or integration;
- physical/operator workstation security;
- authorization policy implementation inside a separately operated gateway;
- guaranteeing that every third-party MCP client supports the issuer handoff.

## Review triggers

Re-review this model for any new protocol adapter, Graph permission, activity
source, ingress/admission mechanism, gateway integration, credential type,
cleanup deletion rule, or CI deployment authority.
