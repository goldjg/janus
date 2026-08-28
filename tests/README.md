# JANUS Tests

## Unit tests (janus/)

Unit tests are in `janus/src/test/java/io/github/goldjg/janus/`:

| Test class | What it covers |
|---|---|
| `RegistrationPolicyTest` | All registration policy validation rules (redirect URIs, grant types, client name, etc.) |
| `GraphClientServiceTest` | Display name generation, tag constants, and naming contracts |

Run unit tests:

```bash
cd janus
mvn test
```

## Integration tests

Integration tests require:
- A live Entra tenant
- A user-assigned managed identity with `Application.ReadWrite.OwnedBy`
- The `JANUS_TENANT_ID` and `AZURE_CLIENT_ID` environment variables set

Integration tests are not included in the standard test suite. They are annotated `@Tag("live-integration")` and must be run explicitly:

```bash
cd janus
mvn test -Dgroups="live-integration"
```

> **Note:** Live integration tests create real Entra app registrations. Ensure the cleanup job or manual cleanup is run after integration test runs.

## End-to-end tests

To test the full DCR flow:

1. Deploy JANUS (see [../docs/deployment.md](../docs/deployment.md))
2. Run the DCR test:
   ```bash
   KEYCLOAK_FQDN="<your-fqdn>"
   curl -s -X POST \
     "https://${KEYCLOAK_FQDN}/realms/janus/clients-registrations/openid-connect" \
     -H "Content-Type: application/json" \
     -d @../examples/mcp/dcr-request.json
   ```
3. Verify the `client_id` in the response is a valid Entra application ID:
   ```bash
   az ad app show --id "<client_id>"
   ```
4. Verify the registration has JANUS tags:
   ```bash
   az ad app show --id "<client_id>" --query tags
   # Expected: ["janus-managed", "janus-realm:janus"]
   ```

## Policy boundary tests

The following cases should return 400 `invalid_redirect_uri`:

```bash
# Non-loopback HTTP redirect URI
curl -X POST "https://${KEYCLOAK_FQDN}/realms/janus/clients-registrations/openid-connect" \
  -H "Content-Type: application/json" \
  -d '{"client_name":"test","redirect_uris":["http://example.com/cb"]}'
# Expected: {"error":"invalid_redirect_uri","error_description":"..."}

# Missing redirect_uris
curl -X POST "https://${KEYCLOAK_FQDN}/realms/janus/clients-registrations/openid-connect" \
  -H "Content-Type: application/json" \
  -d '{"client_name":"test"}'
# Expected: {"error":"invalid_redirect_uri","error_description":"..."}

# Wrong grant type
curl -X POST "https://${KEYCLOAK_FQDN}/realms/janus/clients-registrations/openid-connect" \
  -H "Content-Type: application/json" \
  -d '{"client_name":"test","redirect_uris":["http://localhost:8080/cb"],"grant_types":["client_credentials"]}'
# Expected: {"error":"invalid_client_metadata","error_description":"..."}
```
