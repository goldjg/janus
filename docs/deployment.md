# JANUS Deployment Guide

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| Azure CLI | ≥ 2.50 | `az login` must be run before proceeding |
| PowerShell | 7+ | For bootstrap script |
| Microsoft.Graph PowerShell module | ≥ 2.0 | Installed by bootstrap script if absent |
| Java | 17 | For building the JANUS extension |
| Maven | ≥ 3.8 | For building the JANUS extension |
| Docker | Any recent | For building the container image |

## Step 1: Build the JANUS extension

```bash
cd janus
mvn clean package -DskipTests
```

This produces `target/janus-dcr-provider-<version>.jar`.

## Step 2: Build and push the container image

The JANUS container image extends the official Keycloak image with the JANUS extension JAR.

```bash
# Set your registry details
export ACR_NAME="<your-azure-container-registry-name>"
export IMAGE_TAG="$(git rev-parse --short HEAD)"

# Build
docker build \
  -t "${ACR_NAME}.azurecr.io/janus-keycloak:${IMAGE_TAG}" \
  -f janus/Dockerfile \
  janus/

# Push (requires `az acr login --name $ACR_NAME`)
az acr login --name "${ACR_NAME}"
docker push "${ACR_NAME}.azurecr.io/janus-keycloak:${IMAGE_TAG}"
```

## Step 3: Configure parameters

Copy and edit the parameters file:

```bash
cp infra/parameters.bicepparam.example infra/parameters.bicepparam
```

Fill in all required values. See comments in `parameters.bicepparam.example` for guidance.

Required parameters:

| Parameter | Description |
|---|---|
| `tenantId` | Entra tenant ID |
| `gatewayResourceUri` | App ID URI of the MCP gateway Entra application (`api://<client-id>`) |
| `acrName` | Azure Container Registry name |
| `imageTag` | Container image tag to deploy |
| `keycloakAdminPasswordSecretRef` | Reference to Keycloak admin password in Key Vault |

## Step 4: Run bootstrap (one-time)

The bootstrap script performs one-time setup that cannot be done via Bicep alone:

```powershell
./bootstrap/bootstrap.ps1 `
  -TenantId "<tenant-id>" `
  -SubscriptionId "<subscription-id>" `
  -ResourceGroup "rg-janus-prod" `
  -Location "australiaeast" `
  -ManagedIdentityName "mi-janus-prod"
```

The bootstrap script:

1. Creates the resource group if it does not exist
2. Creates the User-Assigned Managed Identity if it does not exist
3. Assigns `Application.ReadWrite.OwnedBy` Graph app role to the Managed Identity
4. Outputs the Managed Identity client ID and object ID for use in `parameters.bicepparam`

> **Note:** You must have `Application Administrator` or `Global Administrator` Entra role to run the Graph role assignment step. Azure `Owner` on the subscription is not sufficient.

## Step 5: Deploy infrastructure

```bash
az deployment group create \
  --resource-group rg-janus-prod \
  --template-file infra/main.bicep \
  --parameters infra/parameters.bicepparam \
  --name "janus-$(date +%Y%m%d-%H%M%S)"
```

## Step 6: Configure Keycloak realm

After the Keycloak Container App is running, configure the JANUS realm:

```bash
# Get the Keycloak FQDN from the deployment output
KEYCLOAK_FQDN=$(az deployment group show \
  --resource-group rg-janus-prod \
  --name <deployment-name> \
  --query properties.outputs.keycloakFqdn.value -o tsv)

# The bootstrap script can also handle this step
./bootstrap/bootstrap.ps1 `
  -TenantId "<tenant-id>" `
  -SubscriptionId "<subscription-id>" `
  -ResourceGroup "rg-janus-prod" `
  -Location "australiaeast" `
  -KeycloakFqdn "${KEYCLOAK_FQDN}" `
  -ConfigureRealm
```

## Step 7: Verify

```bash
# Check DCR endpoint is live
curl -f "https://${KEYCLOAK_FQDN}/realms/janus/.well-known/openid-configuration"

# Verify JANUS extension is loaded (should include janus-dcr in providers)
curl -f "https://${KEYCLOAK_FQDN}/realms/janus/clients-registrations/providers"
```

## CI/CD deployment

The GitHub Actions workflow (`.github/workflows/build.yml`) automates the build, image push, and deployment steps. See the workflow file for required secrets and environment setup.

## Upgrading

1. Build and push a new container image with the new `imageTag`.
2. Update `imageTag` in `parameters.bicepparam`.
3. Re-run `az deployment group create` (step 5). Container Apps performs a rolling update.

## Rollback

To roll back to a previous image tag:

1. Update `imageTag` in `parameters.bicepparam` to the previous tag.
2. Re-run `az deployment group create`.

Container App revisions also support direct revision management via the Azure Portal or CLI.
