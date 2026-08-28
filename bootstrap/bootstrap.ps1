<#
.SYNOPSIS
    JANUS one-time bootstrap script.

.DESCRIPTION
    Performs one-time prerequisite setup for JANUS that cannot be done via
    Bicep alone, including:
    - Creating the resource group (if absent)
    - Creating the User-Assigned Managed Identity (if absent)
    - Assigning Microsoft Graph Application.ReadWrite.OwnedBy to the identity
    - Optionally configuring the Keycloak realm after deployment

.NOTES
    Requirements:
    - PowerShell 7+
    - Azure CLI (az) >= 2.50 — must be logged in
    - Microsoft.Graph PowerShell module >= 2.0 (installed if absent)
    - Entra role: Application Administrator or Global Administrator (for Graph role assignment)
    - Azure role: Owner or Contributor on the target subscription

.EXAMPLE
    ./bootstrap.ps1 `
      -TenantId "00000000-0000-0000-0000-000000000000" `
      -SubscriptionId "11111111-1111-1111-1111-111111111111" `
      -ResourceGroup "rg-janus-prod" `
      -Location "australiaeast"

.EXAMPLE
    # Also configure the Keycloak realm after deployment
    ./bootstrap.ps1 `
      -TenantId "..." `
      -SubscriptionId "..." `
      -ResourceGroup "rg-janus-prod" `
      -Location "australiaeast" `
      -KeycloakFqdn "keycloak.example.com" `
      -ConfigureRealm
#>

[CmdletBinding(SupportsShouldProcess)]
param(
    [Parameter(Mandatory)]
    [string] $TenantId,

    [Parameter(Mandatory)]
    [string] $SubscriptionId,

    [Parameter(Mandatory)]
    [string] $ResourceGroup,

    [Parameter(Mandatory)]
    [string] $Location,

    [Parameter()]
    [string] $ManagedIdentityName = 'mi-janus',

    [Parameter()]
    [string] $AcrName,

    [Parameter()]
    [string] $KeycloakFqdn,

    [Parameter()]
    [switch] $ConfigureRealm
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ─── Prerequisites ────────────────────────────────────────────────────────────

Write-Host "JANUS Bootstrap" -ForegroundColor Cyan
Write-Host "═══════════════════════════════════════" -ForegroundColor Cyan

# Verify Azure CLI is available and logged in
Write-Host "`n[1/5] Verifying Azure CLI login..." -ForegroundColor Yellow
$azAccount = az account show --subscription $SubscriptionId 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Error "Not logged in to Azure CLI or subscription '$SubscriptionId' not accessible. Run: az login"
}
Write-Host "      Logged in. Subscription: $SubscriptionId" -ForegroundColor Green

# Set the active subscription
az account set --subscription $SubscriptionId
if ($LASTEXITCODE -ne 0) { Write-Error "Failed to set subscription." }

# Install Microsoft.Graph module if absent
Write-Host "`n[2/5] Checking Microsoft.Graph PowerShell module..." -ForegroundColor Yellow
if (-not (Get-Module -ListAvailable -Name Microsoft.Graph.Applications)) {
    Write-Host "      Installing Microsoft.Graph.Applications..." -ForegroundColor Yellow
    Install-Module Microsoft.Graph.Applications -Scope CurrentUser -Force -AllowClobber
}
Import-Module Microsoft.Graph.Applications -ErrorAction Stop
Write-Host "      Microsoft.Graph.Applications available." -ForegroundColor Green

# ─── Resource Group ───────────────────────────────────────────────────────────

Write-Host "`n[3/5] Ensuring resource group '$ResourceGroup'..." -ForegroundColor Yellow
$rgExists = az group exists --name $ResourceGroup --subscription $SubscriptionId
if ($rgExists -eq 'false') {
    if ($PSCmdlet.ShouldProcess($ResourceGroup, 'Create resource group')) {
        az group create --name $ResourceGroup --location $Location --subscription $SubscriptionId
        if ($LASTEXITCODE -ne 0) { Write-Error "Failed to create resource group." }
        Write-Host "      Created resource group." -ForegroundColor Green
    }
} else {
    Write-Host "      Resource group exists." -ForegroundColor Green
}

# ─── User-Assigned Managed Identity ──────────────────────────────────────────

Write-Host "`n[4/5] Ensuring managed identity '$ManagedIdentityName'..." -ForegroundColor Yellow

$miJson = az identity show `
    --name $ManagedIdentityName `
    --resource-group $ResourceGroup `
    --subscription $SubscriptionId `
    2>&1

if ($LASTEXITCODE -ne 0) {
    if ($PSCmdlet.ShouldProcess($ManagedIdentityName, 'Create user-assigned managed identity')) {
        $miJson = az identity create `
            --name $ManagedIdentityName `
            --resource-group $ResourceGroup `
            --location $Location `
            --subscription $SubscriptionId
        if ($LASTEXITCODE -ne 0) { Write-Error "Failed to create managed identity." }
        Write-Host "      Created managed identity." -ForegroundColor Green
    }
} else {
    Write-Host "      Managed identity exists." -ForegroundColor Green
}

$mi = $miJson | ConvertFrom-Json
$miClientId      = $mi.clientId
$miResourceId    = $mi.id
$miPrincipalId   = $mi.principalId

Write-Host "      Client ID:     $miClientId"
Write-Host "      Resource ID:   $miResourceId"
Write-Host "      Principal ID:  $miPrincipalId"

# ─── Graph Role Assignment ────────────────────────────────────────────────────

Write-Host "`n[5/5] Assigning Graph 'Application.ReadWrite.OwnedBy' to managed identity..." -ForegroundColor Yellow
Write-Host "      NOTE: This step requires Entra 'Application Administrator' or 'Global Administrator' role." -ForegroundColor Yellow

# Connect to Microsoft Graph
Connect-MgGraph -TenantId $TenantId -Scopes 'AppRoleAssignment.ReadWrite.All', 'Application.Read.All' -NoWelcome

# Get the Microsoft Graph service principal
$graphSp = Get-MgServicePrincipal -Filter "appId eq '00000003-0000-0000-c000-000000000000'"
if (-not $graphSp) { Write-Error "Could not find Microsoft Graph service principal." }

# Find the Application.ReadWrite.OwnedBy app role
$appRole = $graphSp.AppRoles | Where-Object { $_.Value -eq 'Application.ReadWrite.OwnedBy' }
if (-not $appRole) { Write-Error "Could not find 'Application.ReadWrite.OwnedBy' app role." }

Write-Host "      Graph SP ID:   $($graphSp.Id)"
Write-Host "      App Role ID:   $($appRole.Id)"

# Check if assignment already exists
$existingAssignment = Get-MgServicePrincipalAppRoleAssignment `
    -ServicePrincipalId $miPrincipalId `
    -ErrorAction SilentlyContinue `
    | Where-Object { $_.AppRoleId -eq $appRole.Id -and $_.ResourceId -eq $graphSp.Id }

if ($existingAssignment) {
    Write-Host "      Assignment already exists." -ForegroundColor Green
} elseif ($PSCmdlet.ShouldProcess($miPrincipalId, "Assign Application.ReadWrite.OwnedBy")) {
    $assignment = New-MgServicePrincipalAppRoleAssignment `
        -ServicePrincipalId $miPrincipalId `
        -PrincipalId $miPrincipalId `
        -ResourceId $graphSp.Id `
        -AppRoleId $appRole.Id

    if (-not $assignment) { Write-Error "Failed to create role assignment." }
    Write-Host "      Role assigned successfully." -ForegroundColor Green
}

Disconnect-MgGraph

# ─── Optional ACR creation ────────────────────────────────────────────────────

if ($AcrName) {
    Write-Host "`n[Optional] Ensuring Container Registry '$AcrName'..." -ForegroundColor Yellow
    $acrExists = az acr show --name $AcrName --resource-group $ResourceGroup 2>&1
    if ($LASTEXITCODE -ne 0) {
        if ($PSCmdlet.ShouldProcess($AcrName, 'Create Azure Container Registry')) {
            az acr create `
                --name $AcrName `
                --resource-group $ResourceGroup `
                --sku Basic `
                --admin-enabled false
            if ($LASTEXITCODE -ne 0) { Write-Error "Failed to create ACR." }
            Write-Host "      ACR created." -ForegroundColor Green

            # Assign AcrPull to the managed identity
            $acrId = az acr show --name $AcrName --query id -o tsv
            az role assignment create `
                --role AcrPull `
                --assignee-object-id $miPrincipalId `
                --assignee-principal-type ServicePrincipal `
                --scope $acrId
            Write-Host "      AcrPull assigned to managed identity." -ForegroundColor Green
        }
    } else {
        Write-Host "      ACR exists." -ForegroundColor Green
    }
}

# ─── Optional Keycloak realm configuration ────────────────────────────────────

if ($ConfigureRealm -and $KeycloakFqdn) {
    Write-Host "`n[Optional] Configuring Keycloak realm on '$KeycloakFqdn'..." -ForegroundColor Yellow
    $realm = Import-Realm -KeycloakFqdn $KeycloakFqdn
    Write-Host "      Realm configured." -ForegroundColor Green
}

# ─── Summary ─────────────────────────────────────────────────────────────────

Write-Host "`n═══════════════════════════════════════" -ForegroundColor Cyan
Write-Host "Bootstrap complete." -ForegroundColor Cyan
Write-Host ""
Write-Host "Add these values to infra/parameters.bicepparam:" -ForegroundColor White
Write-Host "  managedIdentityClientId  = '$miClientId'" -ForegroundColor White
Write-Host "  managedIdentityResourceId = '$miResourceId'" -ForegroundColor White
Write-Host ""
Write-Host "Next step: deploy infrastructure" -ForegroundColor White
Write-Host "  az deployment group create \"
Write-Host "    --resource-group $ResourceGroup \"
Write-Host "    --template-file infra/main.bicep \"
Write-Host "    --parameters infra/parameters.bicepparam" -ForegroundColor White
