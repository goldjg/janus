# Security model

## Primary invariant

> No JANUS component may issue, proxy, cache, exchange, re-sign, inspect for
> reuse, or store the bearer token used to access the protected MCP gateway.

Microsoft Entra ID is the authorization server and token issuer. The MCP
gateway validates and authorizes Entra tokens independently. Keycloak is only a
legacy registration-protocol adapter.

## What registration grants

A successful request grants one thing: an Entra public-client application ID.
It does not grant:

- tenant or gateway consent;
- user/group assignment;
- a gateway role;
- a client secret or certificate;
- an access or refresh token;
- acceptance by Conditional Access;
- permission to invoke an MCP tool.

## Generated client properties

JANUS-generated applications are constrained to:

- `AzureADMyOrg` single-tenant audience;
- public-client redirect configuration;
- authorization code flow metadata;
- exact policy-approved redirects;
- explicitly configured gateway delegated scopes only;
- no password or key credentials;
- no Graph permissions unrelated to the gateway;
- explicit JANUS environment/realm/lifecycle markers;
- collision-resistant, operationally recognizable display names.

Declaring `requiredResourceAccess` identifies a requested API permission but
does not grant consent, assignment or gateway authorization.

## DCR admission and hostile input

The endpoint must not operate as an anonymous unlimited tenant-object factory.
Production operation requires configured admission and bounded creation.

Before Graph provisioning, JANUS rejects:

- requests above the total byte limit;
- unknown or unsupported metadata;
- missing/unsafe client names;
- missing, duplicate, malformed or unapproved redirect URIs;
- URI fragments, userinfo, wildcards and nonnumeric loopback ports;
- confidential-client authentication methods;
- grants or response types other than authorization code/code;
- scopes outside the configured gateway allowlist;
- requests that fail admission, idempotency or creation-rate policy;
- missing or malformed critical tenant/resource configuration.

Application-level admission is required even with edge throttling. Source IP
limits alone are not an identity mechanism and can be unreliable behind shared
proxies.

## Runtime identity and Graph permission

The Container App obtains a Microsoft Graph token from Azure managed identity.
No runtime Graph client secret is stored.

Required application permission:

| Permission | Why | Important limitation |
|---|---|---|
| `Application.ReadWrite.OwnedBy` | Create and manage applications owned by the JANUS runtime identity | It can list all tenant applications/service principals and can fully manage owned objects, including credentials; JANUS code must never exercise credential creation |

Microsoft's permission reference documents this scope and warns that
credential-managing application permissions are sensitive:
[Microsoft Graph permissions reference](https://learn.microsoft.com/graph/permissions-reference#applicationreadwriteownedby).

The runtime identity must not receive `Application.ReadWrite.All`,
`Directory.ReadWrite.All`, `AppRoleAssignment.ReadWrite.All`, delegated Graph
permissions, or permission-grant authority.

Optional access to sign-in reports for lifecycle evidence is a separate design
choice. It expands read authority and has licensing/retention dependencies; the
safe default is to retain applications when that evidence is unavailable.

## Gateway validation

The gateway is outside JANUS and must use mature framework verification for:

- exact Entra issuer and tenant;
- expected audience;
- allowed algorithms, signature and key rollover;
- `nbf` and `exp` with bounded clock skew;
- expected delegated-user versus app-only subject;
- assignment requirement;
- group admission, including Entra group-overage indicators;
- gateway delegated scopes or application roles.

Tokens issued slightly in the future require an explicit skew policy. `iat`
alone does not prevent replay. Missing or malformed authorization claims fail
closed. A Graph overage fallback, if the gateway uses one, must be bounded and
cached with a documented revocation window rather than called on every request.

## Logging

Logs use structured fields and correlation IDs. Safe operational identifiers
include tenant/environment identifiers, Entra object/application IDs, policy
decision codes, lifecycle reasons and Graph status categories.

Never log access/refresh tokens, authorization codes, client credentials,
managed-identity tokens, full JWTs, raw hostile metadata or upstream Graph
response bodies.

## Lifecycle safety

Display names are never deletion evidence. Cleanup requires positive markers
and Graph ownership, honors an explicit exclusion marker, defaults to dry-run,
bounds each run, and rechecks immediately before deletion.

Creation time is not last use. Entra sign-in reports are conditional evidence
subject to licence, retention and latency and do not prove successful gateway
access. Missing or ambiguous evidence is a retain decision.

## CI/CD boundary

Pull requests receive read-only repository permissions and no Azure identity.
Production deployment uses GitHub OIDC federation constrained to the repository
and protected environment. Images are promoted by immutable digest. Long-lived
Azure client secrets are forbidden.

## Security verification status

Unit and static tests prove local policy and payload contracts. They do not
prove real Keycloak adapter selection, Graph/Entra behavior, MCP client issuer
handoff, Conditional Access, gateway authorization, or lifecycle report
availability. Those require explicit opt-in tests in a dedicated tenant.
