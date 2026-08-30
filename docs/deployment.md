# Deployment guide

The supported production path is: tenant bootstrap, protected GitHub
Environment configuration, then the OIDC deployment workflow. JANUS does not
use a GitHub client secret or a runtime Microsoft Graph client secret.

## Prerequisites

- PowerShell 7, Azure CLI, and `Microsoft.Graph.Authentication` 2.x
- an Azure subscription and commercial Microsoft Entra tenant
- a GitHub repository whose `production` Environment can require reviewers
- tenant authority to create or inspect applications, service principals,
  federated credentials, app-role assignments, and admin consent
- Azure authority to create the prerequisite resource group, ACR, user-assigned
  identity, and resource-scoped role assignments

The initial implementation assumes Azure commercial-cloud endpoints. Validate
sovereign-cloud endpoints and service availability separately.

## 1. Bootstrap with a dry run

```powershell
./bootstrap/bootstrap.ps1 `
  -TenantId '<tenant-uuid>' `
  -SubscriptionId '<subscription-uuid>' `
  -ResourceGroup 'rg-janus-prod' `
  -Location 'uksouth' `
  -EnvironmentName 'prod' `
  -AcrName '<globally-unique-acr-name>' `
  -GitHubOwner '<owner>' `
  -GitHubRepository '<repository>' `
  -GitHubEnvironment 'production' `
  -GatewayDisplayName 'JANUS MCP Gateway' `
  -WhatIf -Verbose
```

Review every proposed tenant and Azure change. Then rerun without `-WhatIf`;
the script uses PowerShell `ShouldProcess`, safe discovery, and idempotent
updates where practical. To reuse a gateway application, pass its client ID as
`-GatewayApplicationClientId`.

Bootstrap creates or locates only prerequisites: resource group, ACR, runtime
user-assigned identity, gateway application/service principal, GitHub
deployment application/service principal, federated credential, scoped Azure
roles, and the runtime identity's `Application.ReadWrite.OwnedBy` Graph role.
It does not deploy JANUS and emits no reusable credential.

Save the JSON output if desired:

```powershell
./bootstrap/bootstrap.ps1 @parameters -OutputJsonPath './janus-bootstrap-output.json'
```

Treat this output as operational configuration even though it contains
identifiers rather than credentials. Do not commit it.

## 2. Configure GitHub

Create and protect the Environment named during bootstrap. Add the printed
`githubVariables` as repository or Environment variables, including:

- `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`, `AZURE_DEPLOYMENT_CLIENT_ID`
- `AZURE_RESOURCE_GROUP`, `AZURE_LOCATION`, `JANUS_ENVIRONMENT_NAME`
- `JANUS_GATEWAY_CLIENT_ID`, `JANUS_GATEWAY_RESOURCE_URI`
- `JANUS_ALLOWED_GATEWAY_SCOPES`
- `JANUS_RUNTIME_MI_CLIENT_ID`, `JANUS_RUNTIME_MI_RESOURCE_ID`
- `ACR_NAME`
- `JANUS_CLEANUP_RETENTION_DAYS` and `JANUS_CLEANUP_DRY_RUN`
- `JANUS_EXTERNAL_INGRESS_ENABLED`

Add two independent high-entropy Environment secrets:

- `KEYCLOAK_ADMIN_PASSWORD`
- `KEYCLOAK_POSTGRES_PASSWORD`

The Keycloak/PostgreSQL passwords are genuine persistent secrets, stored as
protected GitHub Environment secrets and Azure Container Apps secrets. JANUS
has no OAuth or Graph client secret, so Key Vault is not introduced solely as
decoration. Organisations with an existing approved secret platform can adapt
the Bicep module.

Optional variables include explicit redirect rules and the three existing
network resource IDs shown in the workflow. Configure all three network IDs or
none. Keep production deployment behind required Environment reviewers.

## 3. Validate and deploy

Pull requests run Java tests, bootstrap safety checks, Maven dependency
analysis, and Bicep compilation without Azure authentication or deployment.
The workflow builds and scans an image, publishes it to ACR, resolves the
immutable digest, and passes that digest to Bicep.

Use `workflow_dispatch` for deliberate production deployment. Push-to-main is
also wired to the protected `production` Environment; required reviewers remain
the approval boundary.

The deployment creates:

- Log Analytics and ACR diagnostics
- a VNet, delegated subnets, and PostgreSQL private DNS unless existing IDs are supplied
- a Container Apps environment and JANUS Container App
- PostgreSQL Flexible Server for persistent Keycloak state
- the scheduled cleanup Container Apps Job

The image imports the minimal `janus` realm on first startup. Runtime
registration policy comes from fail-closed environment settings rather than
the realm export.

## 4. Issue bounded registration admission

JANUS never supports anonymous unlimited application creation. After deployment,
an administrator creates a short-lived Keycloak initial access token for the
`janus` realm with a small registration count and distributes it only to an
approved MCP client operator. Supply it as:

```http
Authorization: Bearer <initial-access-token>
```

The DCR URL is the workflow's `dcrEndpoint` output. Creating the admission
token is deliberately an operator action because its expiry and object budget
are security decisions.

## 5. Verify the handoff

1. Confirm the active Container App revision is healthy.
2. Submit an admitted request based on `examples/mcp/dcr-request.json`.
3. Inspect the returned `client_id` in Entra and confirm it is single tenant,
   public, credential-free, marked with all JANUS ownership tags, and declares
   only the approved gateway delegated permission.
4. Run Authorization Code with PKCE directly against the permitted Entra tenant.
5. Confirm the gateway token issuer is Entra, its audience is the gateway, and
   JANUS never sees the authorization code or token.
6. Confirm an unassigned or non-member user is denied by the gateway even after
   successful registration.

These live checks are opt-in and should use a dedicated test client/operator.

## Network exposure

Public ingress defaults off. If DCR must be internet reachable, initial-access
tokens remain mandatory and an edge control such as Azure Front Door WAF rate
limiting should bound requests across all replicas. Container Apps CIDR rules
can narrow sources but are not a general distributed rate limiter. Do not
enable public ingress merely to make a smoke test convenient.

The supplied deployment also defaults to one replica because idempotency and
secondary rate counters are process-local. Increase replicas only with a
reviewed distributed edge limit and an accepted cross-replica duplicate/race
strategy.

## Cleanup enablement

Cleanup defaults to dry-run. The initial job will retain an old application
unless all ownership markers and fresh, unambiguous activity-evidence markers
exist. Review [lifecycle.md](lifecycle.md) before attaching a trusted sign-in
observer or setting `JANUS_CLEANUP_DRY_RUN=false`.

## Local validation

```bash
cd janus
mvn -B clean verify
cd ..
az bicep build --file infra/main.bicep --stdout >/dev/null
pwsh -NoProfile -File bootstrap/Test-BootstrapStatic.ps1
docker build -t janus:local janus
```

On an ARM64 Raspberry Pi, the last command builds the native ARM64 image. The
GitHub workflow builds the production `linux/amd64` image.

Rollback, incident response, and removal are in [operations.md](operations.md).
