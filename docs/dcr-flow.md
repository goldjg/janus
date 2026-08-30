# DCR-to-Entra flow

## Preconditions

- The operator has configured one canonical Entra tenant UUID.
- The gateway resource URI and application/client UUID are configured.
- Every accepted scope URI maps to an existing delegated permission UUID on
  that gateway application.
- Redirect rules are explicit; the default configuration is fail-closed.
- The caller holds a bounded, expiring Keycloak initial access token.
- The runtime managed identity has only `Application.ReadWrite.OwnedBy`.

## Sequence

```text
MCP client             JANUS DCR adapter       provisioning core       Graph / Entra
    |                         |                        |                       |
    | POST + initial token    |                        |                       |
    |------------------------>|                        |                       |
    |                         | bound JSON parse       |                       |
    |                         | admission + policy     |                       |
    |                         | rate/idempotency       |                       |
    |                         |----------------------->|                       |
    |                         |                        | normalized request    |
    |                         |                        |---------------------->|
    |                         |                        | 201 appId/objectId    |
    |                         |                        |<----------------------|
    |                         | consume admission use  |                       |
    | 201 Entra client_id     |                        |                       |
    |<------------------------|                        |                       |

    |---------------- JANUS is now absent -----------------------------------|
    | authorization code + S256 PKCE -----------------------------> Entra     |
    | <------------------------------- Entra-issued access token ------------|
    | bearer token -----------------------------------------------> gateway   |
```

Invalid input, failed admission, exhausted rate/capacity or configuration error
must fail before the Graph create operation. A Graph failure must not consume a
successful-registration result or be cached as a created client.

## Endpoint

```text
POST /realms/{realm}/clients-registrations/openid-connect
Authorization: Bearer <Keycloak initial access token>
Content-Type: application/json
```

Initial access tokens are intentionally bounded and expiring. If a target MCP
client cannot supply one, do not switch JANUS to anonymous unlimited mode.
Instead use an explicitly designed bootstrap/admission adapter or pre-register
the client, and document the compatibility exception.

## Accepted metadata

| DCR field | Required | Policy |
|---|---:|---|
| `client_name` | Yes | Bounded ASCII safe character set |
| `redirect_uris` | Yes | Nonempty, unique, bounded and matched against structured configured rules |
| `grant_types` | No | Omitted or exactly `authorization_code` |
| `response_types` | No | Omitted or exactly `code` |
| `token_endpoint_auth_method` | No | Omitted or exactly `none` |
| `scope` | Yes | Unique space-delimited exact values from the configured gateway scope map |

Unknown fields, malformed types, duplicate values, fragments, userinfo,
wildcards outside the controlled loopback syntax, unapproved schemes and
oversized bodies/fields are rejected.

Loopback rules use an explicit template such as:

```text
http://localhost:{port}/*
http://127.0.0.1:{port}/*
http://[::1]:{port}/*
```

HTTP requires a loopback host and explicit numeric port. HTTPS and custom
native-app schemes use exact URI matching; prefix matching is not used.

## Scope configuration

`JANUS_ALLOWED_GATEWAY_SCOPES` is a comma-separated mapping:

```text
api://<gateway-client-id>/Mcp.Access=<delegated-permission-uuid>
```

The URI is the DCR scope value. The UUID is the corresponding delegated
permission definition ID used in Graph `requiredResourceAccess`. Both the
gateway application/client UUID and resource URI are required because Graph
cannot build the permission declaration from an App ID URI alone.

JANUS does not grant consent or assignment. The declaration only identifies
what the generated client may request through the tenant's normal consent and
gateway-admission controls.

## Graph application mapping

| Entra field | Source/rule |
|---|---|
| `displayName` | sanitized realm/client name plus collision-resistant suffix |
| `signInAudience` | `AzureADMyOrg` |
| `isFallbackPublicClient` | `true` |
| `publicClient.redirectUris` | normalized approved DCR redirects |
| `requiredResourceAccess.resourceAppId` | configured gateway application/client UUID |
| delegated resource access IDs | permission UUIDs for requested approved scopes |
| `tags` / `notes` | explicit JANUS schema, realm/environment and creation metadata |
| `passwordCredentials` / `keyCredentials` | never supplied |

## Response

The response contains the real Entra `client_id`, accepted public-client
metadata and requested approved scope. It never contains `client_secret`.

The caller must use the configured Entra tenant issuer, not Keycloak, for the
authorization request. Real-client issuer handoff remains an opt-in integration
test requirement.

## Idempotency boundary

Equivalent admitted requests are coalesced and cached for a bounded interval
inside one process. This reduces accidental duplicate creation but is not a
durable cross-replica idempotency guarantee. Initial-access-token quotas, edge
limits and tenant monitoring remain required in multi-replica production.

## Errors

| Status | Meaning |
|---:|---|
| 400 | malformed or policy-invalid registration metadata |
| 401 | missing or rejected initial access admission |
| 429 | source/global rate or process creation capacity exhausted |
| 500/502/503 | configuration or Graph/provisioning failure, with safe correlation reference |

Policy and parser errors are unit tested. The deployed workflow additionally
smoke-tests that the custom route rejects an unauthenticated create with 401;
full live Graph success remains opt-in.
