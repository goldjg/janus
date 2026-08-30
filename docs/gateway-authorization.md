# Reference gateway authorization contract

JANUS does not authorize MCP requests and does not validate gateway bearer
tokens. This document defines the contract an independently operated gateway
must satisfy for the JANUS architecture to be secure.

## Validation order

Use mature OAuth/JWT middleware with an explicit algorithm allowlist and Entra
metadata/JWKS retrieval. After cryptographic verification, enforce:

1. issuer equals the configured tenant's Entra v2 issuer;
2. tenant claim identifies the permitted tenant;
3. audience equals the gateway application ID URI/client ID as designed;
4. `nbf` and `exp` are valid within a small documented clock-skew window;
5. required subject and authorization claims have the expected types;
6. user tokens and app-only tokens are distinguished and admitted only on the
   intended route;
7. enterprise-application assignment and required scopes/app roles are present;
8. group admission is satisfied or a safe group-overage path is used.

Do not accept a token because its signature is valid alone. Do not use `iat` as
replay prevention. A token issued slightly in the future may be accepted only
within the same documented clock-skew policy used for temporal claims.

## Expected negative tests

- expired `exp` and not-yet-valid `nbf`;
- excessive future `iat` and acceptable bounded skew;
- unexpected issuer, tenant or audience;
- missing/malformed time, subject, scope, role or group claims;
- duplicated/ambiguous claims as exposed by the chosen framework;
- group-overage indicators without a configured resolution strategy;
- required group absent;
- app-only token on a user-only route;
- delegated user token on a workload-only route;
- replay of a still-valid bearer token demonstrates that `iat` alone does not
  stop reuse.

Tests of decoded claims do not replace signature, algorithm-confusion, JWKS
rollover and metadata-pinning integration tests.

## Group overage

Entra may omit the full `groups` array and emit overage indicators. The gateway
must not treat an absent groups array as membership.

Supported strategies are:

- deny closed and require token/group configuration changes; or
- resolve membership through Graph with a separately authorized gateway
  identity, bounded caching and a documented maximum revocation delay.

Avoid Graph lookup on every MCP request. A cache improves availability but
extends revocation latency; choose and monitor that trade-off explicitly. JANUS
does not perform this lookup.

## Assignment and consent

The gateway enterprise application should require assignment. Operators assign
only intended users/groups and control consent for the exposed delegated scope.
A client created by JANUS receives neither assignment nor consent automatically.

## Operational evidence

Gateway authorization logs may record safe token-derived identifiers and policy
decision codes, but never the raw bearer token or full JWT. Correlation with a
JANUS registration ID is optional and must not turn JANUS into a session or
token-tracking service.
