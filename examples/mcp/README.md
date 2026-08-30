# JANUS MCP Client Examples

## Dynamic Client Registration

### Register a new MCP client

```bash
KEYCLOAK_FQDN="<your-keycloak-fqdn>"

curl -s -X POST \
  "https://${KEYCLOAK_FQDN}/realms/janus/clients-registrations/openid-connect" \
  -H "Authorization: Bearer ${JANUS_INITIAL_ACCESS_TOKEN}" \
  -H "Content-Type: application/json" \
  -d @dcr-request.json \
  | jq .
```

**Example request body** (`dcr-request.json`):
```json
{
  "client_name": "Claude Code",
  "redirect_uris": [
    "http://localhost:7777/oauth/callback",
    "http://127.0.0.1:7777/oauth/callback"
  ],
  "grant_types": ["authorization_code"],
  "response_types": ["code"],
  "token_endpoint_auth_method": "none",
  "scope": "api://00000000-0000-0000-0000-000000000000/access_as_user"
}
```

**Example response:**
```json
{
  "client_id": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
  "client_name": "janus-janus-claude-code-a1b2c3d4",
  "redirect_uris": [
    "http://localhost:7777/oauth/callback",
    "http://127.0.0.1:7777/oauth/callback"
  ],
  "grant_types": ["authorization_code"],
  "response_types": ["code"],
  "token_endpoint_auth_method": "none",
  "scope": "api://00000000-0000-0000-0000-000000000000/access_as_user"
}
```

The `client_id` in the response is the real Entra application (client) ID. Use this to authenticate against Microsoft Entra ID directly.

## After registration

1. Use the `client_id` from the DCR response as your OAuth client ID.
2. Direct the MCP client to the Entra authorization endpoint:
   ```
   https://login.microsoftonline.com/<tenant-id>/oauth2/v2.0/authorize
   ```
3. The MCP client authenticates directly against Entra (no JANUS involvement).
4. Include the resulting Entra access token in calls to the MCP gateway.

## Allowed redirect URIs

By default, JANUS allows loopback redirect URIs only:

- `http://localhost:<any-port>/...`
- `http://127.0.0.1:<any-port>/...`
- `http://[::1]:<any-port>/...`

These are the standard patterns used by local MCP clients like Claude Code.

Custom exact patterns can be added via the
`JANUS_ALLOWED_REDIRECT_URI_PATTERNS` environment variable. Compatibility
exceptions must be explicit; JANUS does not accept arbitrary URI prefixes.

## Validation errors

| Error | Description |
|---|---|
| `invalid_redirect_uri` | Redirect URI not on allowlist, malformed, has fragment, or non-loopback HTTP |
| `invalid_client_metadata` | client_name missing or invalid, grant_type not authorization_code, etc. |
| `invalid_token` | A bounded, unexpired Keycloak initial access token was not supplied |
| `temporarily_unavailable` | A rate or process creation limit was reached |
| `server_error` | Internal error (check logs for correlation ID) |
