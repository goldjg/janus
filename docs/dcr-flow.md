# JANUS DCR Flow

## Overview

This document describes the Dynamic Client Registration (RFC 7591) flow as implemented by JANUS.

## Sequence diagram

```
MCP Client           JANUS/Keycloak           Microsoft Graph        Microsoft Entra ID
     │                     │                         │                        │
     │  POST /realms/janus/clients-registrations/openid-connect               │
     │  { client_name, redirect_uris, grant_types, … }                        │
     │────────────────────►│                         │                        │
     │                     │                         │                        │
     │                     │ RegistrationPolicy.validate()                    │
     │                     │ (redirect URIs, grant types, scopes, etc.)       │
     │                     │                         │                        │
     │                     │ [on validation failure]                          │
     │◄────────────────────│                         │                        │
     │  HTTP 400            │                         │                        │
     │  { error: "invalid_client_metadata", … }       │                        │
     │                     │                         │                        │
     │                     │ [on validation success]                          │
     │                     │  POST /v1.0/applications                         │
     │                     │  (Managed Identity credential)                   │
     │                     │────────────────────────►│                        │
     │                     │                         │                        │
     │                     │  HTTP 201 Created        │                        │
     │                     │  { id, appId, … }        │                        │
     │                     │◄────────────────────────│                        │
     │                     │                         │                        │
     │  HTTP 201 Created    │                         │                        │
     │  { client_id: <appId>, … }                     │                        │
     │◄────────────────────│                         │                        │
     │                     │                         │                        │
     │─────────────── JANUS is now out of the auth path ───────────────────── │
     │                     │                         │                        │
     │  Authorization request (auth code + PKCE)     │                        │
     │────────────────────────────────────────────────────────────────────────►
     │                     │                         │                        │
     │  Authorization code                           │                        │
     │◄───────────────────────────────────────────────────────────────────────
     │                     │                         │                        │
     │  Token request (code + verifier)              │                        │
     │────────────────────────────────────────────────────────────────────────►
     │                     │                         │                        │
     │  Access token (Entra-issued, Entra-signed)    │                        │
     │◄───────────────────────────────────────────────────────────────────────
     │                     │                         │                        │
```

## DCR request fields

JANUS accepts the following RFC 7591 fields in a DCR request. All other fields are rejected with `invalid_client_metadata`.

| Field | Required | Accepted values | Notes |
|---|---|---|---|
| `client_name` | Yes | String, 1–64 chars, `[A-Za-z0-9 _\-\.]` | Used as part of the Entra display name |
| `redirect_uris` | Yes | Array of URIs | Must match configured allowlist |
| `grant_types` | No | `["authorization_code"]` | Defaults to `["authorization_code"]` if absent |
| `response_types` | No | `["code"]` | Defaults to `["code"]` if absent |
| `token_endpoint_auth_method` | No | `"none"` | Must be `"none"` (public client). Defaults to `"none"` |
| `scope` | No | Space-delimited subset of configured gateway scopes | Restricted to allowed scopes |

## Redirect URI policy

Redirect URIs are validated against a configurable allowlist. The built-in defaults support common MCP client patterns:

| Pattern | Example | Notes |
|---|---|---|
| Localhost HTTP (any port) | `http://localhost:PORT/callback` | PKCE required; loopback only |
| IPv4 loopback (any port) | `http://127.0.0.1:PORT/callback` | PKCE required |
| IPv6 loopback (any port) | `http://[::1]:PORT/callback` | PKCE required |

Additional patterns (e.g. custom schemes for native apps) are configurable via the `JANUS_ALLOWED_REDIRECT_URI_PATTERNS` environment variable.

Explicitly rejected:
- `http://` URIs with non-loopback hostnames
- Wildcard URIs (`https://*.example.com/...`)
- Data URIs, JavaScript URIs
- Fragments in redirect URIs

## DCR response fields

On success, JANUS returns a DCR response containing:

| Field | Source | Notes |
|---|---|---|
| `client_id` | Entra `appId` | The Entra application client ID |
| `client_name` | Normalised display name | The Entra display name assigned |
| `redirect_uris` | From validated request | Echo of accepted redirect URIs |
| `grant_types` | `["authorization_code"]` | Always public client |
| `response_types` | `["code"]` | Always code flow |
| `token_endpoint_auth_method` | `"none"` | Always public client |

`client_secret` is never included in the response.

## Error responses

JANUS returns RFC 7591-compliant error responses:

| HTTP status | `error` value | Condition |
|---|---|---|
| 400 | `invalid_redirect_uri` | Redirect URI not on allowlist or malformed |
| 400 | `invalid_client_metadata` | Validation failure (name, grant type, auth method, etc.) |
| 500 | `server_error` | Microsoft Graph returned an unexpected error |

## Entra application created

JANUS creates the following application registration in Entra:

```json
{
  "displayName": "janus-<realm>-<sanitised-name>-<uuid-prefix>",
  "isFallbackPublicClient": true,
  "publicClient": {
    "redirectUris": ["<validated redirect URIs>"]
  },
  "signInAudience": "AzureADMyOrg",
  "tags": ["janus-managed", "janus-realm:<realm>"],
  "notes": "Created by JANUS. Realm: <realm>. Created: <ISO8601>."
}
```

No API permissions (`requiredResourceAccess`) are added. No client secrets or certificates are created. No owners are assigned (JANUS uses `Application.ReadWrite.OwnedBy` ownership).

## Keycloak integration notes

Keycloak routes the `POST /clients-registrations/openid-connect` request to `JanusDCRProvider`. The provider is registered via the Keycloak SPI and is selected for the `janus` realm.

Keycloak's own internal client registry is not used as the source of truth for MCP gateway access. The Entra `appId` (returned in the DCR response) is the client identity for MCP gateway authentication.

JANUS does not configure Keycloak as an identity provider or authorisation server for the MCP gateway.
