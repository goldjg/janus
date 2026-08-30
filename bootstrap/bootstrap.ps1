#requires -Version 7.0
<#
.SYNOPSIS
Creates the one-time Azure and Entra prerequisites for JANUS.

.DESCRIPTION
The script is intentionally prerequisite-only. It does not deploy JANUS or
contact its DCR endpoint. Every Azure/Graph mutation is idempotent and guarded
by ShouldProcess, so -WhatIf performs validation and read-only discovery while
printing the changes that would be made.

The runtime identity receives Microsoft Graph Application.ReadWrite.OwnedBy,
never Application.ReadWrite.All. GitHub authenticates with an environment-
scoped workload identity federation credential and receives no client secret.

.EXAMPLE
./bootstrap.ps1 -TenantId $tenant -SubscriptionId $subscription `
  -ResourceGroup rg-janus-prod -Location uksouth -EnvironmentName prod `
  -AcrName janusprod1234 -GitHubOwner example -GitHubRepository janus -WhatIf

.EXAMPLE
./bootstrap.ps1 -TenantId $tenant -SubscriptionId $subscription `
  -ResourceGroup rg-janus-prod -Location uksouth -EnvironmentName prod `
  -AcrName janusprod1234 -GitHubOwner example -GitHubRepository janus `
  -GatewayDisplayName 'JANUS MCP Gateway' -Confirm
#>

[CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'High')]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$')]
    [string] $TenantId,

    [Parameter(Mandatory)]
    [ValidatePattern('^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$')]
    [string] $SubscriptionId,

    [Parameter(Mandatory)]
    [ValidatePattern('^[A-Za-z0-9._()\-]{1,90}$')]
    [string] $ResourceGroup,

    [Parameter(Mandatory)]
    [ValidatePattern('^[a-z0-9]+$')]
    [string] $Location,

    [Parameter(Mandatory)]
    [ValidatePattern('^[a-z0-9-]{3,20}$')]
    [string] $EnvironmentName,

    [Parameter(Mandatory)]
    [ValidatePattern('^[a-zA-Z0-9]{5,50}$')]
    [string] $AcrName,

    [Parameter(Mandatory)]
    [ValidatePattern('^[A-Za-z0-9_.-]+$')]
    [string] $GitHubOwner,

    [Parameter(Mandatory)]
    [ValidatePattern('^[A-Za-z0-9_.-]+$')]
    [string] $GitHubRepository,

    [Parameter()]
    [ValidatePattern('^[A-Za-z0-9_.-]+$')]
    [string] $GitHubEnvironment = 'production',

    [Parameter()]
    [ValidatePattern('^[A-Za-z0-9_.()\- ]{3,120}$')]
    [string] $GatewayDisplayName = 'JANUS MCP Gateway',

    [Parameter()]
    [ValidatePattern('^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$')]
    [string] $GatewayApplicationClientId,

    [Parameter()]
    [ValidatePattern('^[A-Za-z][A-Za-z0-9._-]{0,119}$')]
    [string] $GatewayScopeValue = 'access_as_user',

    [Parameter()]
    [ValidatePattern('^[A-Za-z][A-Za-z0-9._-]{0,119}$')]
    [string] $GatewayAppRoleValue,

    [Parameter()]
    [ValidatePattern('^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$')]
    [string] $GatewayAccessGroupObjectId,

    [Parameter()]
    [ValidatePattern('^[A-Za-z0-9_.()\- ]{3,120}$')]
    [string] $ManagedIdentityName = "janus-$EnvironmentName-runtime",

    [Parameter()]
    [ValidatePattern('^[A-Za-z0-9_.()\- ]{3,120}$')]
    [string] $DeploymentIdentityDisplayName = "janus-$EnvironmentName-github-deployment",

    [Parameter()]
    [ValidateRange(1, 3650)]
    [int] $CleanupRetentionDays = 30,

    [Parameter()]
    [string] $OutputJsonPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

function Write-Step {
    param([Parameter(Mandatory)][string] $Message)
    Write-Host "`n==> $Message" -ForegroundColor Cyan
}

function Assert-Prerequisites {
    if ($PSVersionTable.PSVersion.Major -lt 7) {
        throw 'JANUS bootstrap requires PowerShell 7 or later.'
    }
    if (-not (Get-Command az -ErrorAction SilentlyContinue)) {
        throw 'Azure CLI is required. Install it from https://learn.microsoft.com/cli/azure/install-azure-cli.'
    }
    $graphModule = Get-Module -ListAvailable Microsoft.Graph.Authentication |
        Where-Object { $_.Version -ge [version]'2.0.0' } |
        Sort-Object Version -Descending |
        Select-Object -First 1
    if (-not $graphModule) {
        throw "Microsoft.Graph.Authentication 2.x is required. Install it explicitly with: Install-Module Microsoft.Graph.Authentication -Scope CurrentUser"
    }
    Import-Module Microsoft.Graph.Authentication -MinimumVersion 2.0.0 -ErrorAction Stop
}

function Invoke-AzJson {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string[]] $Arguments,
        [switch] $AllowFailure
    )
    Write-Verbose "az $($Arguments -join ' ')"
    $raw = & az @Arguments --only-show-errors --output json 2>&1
    if ($LASTEXITCODE -ne 0) {
        if ($AllowFailure) { return $null }
        throw "Azure CLI failed: $($raw -join [Environment]::NewLine)"
    }
    $text = ($raw -join [Environment]::NewLine).Trim()
    if ([string]::IsNullOrWhiteSpace($text)) { return $null }
    return $text | ConvertFrom-Json -Depth 100
}

function Invoke-AzMutation {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string] $Target,
        [Parameter(Mandatory)][string] $Action,
        [Parameter(Mandatory)][string[]] $Arguments
    )
    if (-not $PSCmdlet.ShouldProcess($Target, $Action)) { return $null }
    return Invoke-AzJson -Arguments $Arguments
}

function Get-GraphCollection {
    param([Parameter(Mandatory)][string] $Uri)
    $response = Invoke-MgGraphRequest -Method GET -Uri $Uri -OutputType PSObject
    if ($null -eq $response.value) { return @() }
    return @($response.value)
}

function Invoke-GraphMutation {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string] $Target,
        [Parameter(Mandatory)][string] $Action,
        [Parameter(Mandatory)][ValidateSet('POST', 'PATCH')][string] $Method,
        [Parameter(Mandatory)][string] $Uri,
        [Parameter(Mandatory)][hashtable] $Body
    )
    if (-not $PSCmdlet.ShouldProcess($Target, $Action)) { return $null }
    $json = $Body | ConvertTo-Json -Depth 20 -Compress
    Write-Verbose "$Method $Uri"
    return Invoke-MgGraphRequest -Method $Method -Uri $Uri -Body $json -ContentType 'application/json' -OutputType PSObject
}

function Ensure-AzureRoleAssignment {
    param(
        [Parameter(Mandatory)][string] $PrincipalObjectId,
        [Parameter(Mandatory)][string] $PrincipalType,
        [Parameter(Mandatory)][string] $Role,
        [Parameter(Mandatory)][string] $Scope
    )
    $existing = @(Invoke-AzJson -Arguments @(
        'role', 'assignment', 'list', '--assignee-object-id', $PrincipalObjectId,
        '--role', $Role, '--scope', $Scope, '--subscription', $SubscriptionId
    ))
    if ($existing.Count -gt 0) {
        Write-Verbose "Azure role '$Role' already assigned at '$Scope'."
        return
    }
    [void](Invoke-AzMutation -Target "$PrincipalObjectId at $Scope" -Action "Assign Azure role $Role" -Arguments @(
        'role', 'assignment', 'create', '--assignee-object-id', $PrincipalObjectId,
        '--assignee-principal-type', $PrincipalType, '--role', $Role,
        '--scope', $Scope, '--subscription', $SubscriptionId
    ))
}

function Ensure-GatewayApplication {
    Write-Step 'Locating or creating the MCP gateway application'
    if ($GatewayApplicationClientId) {
        $escapedId = [Uri]::EscapeDataString("appId eq '$GatewayApplicationClientId'")
        $matches = Get-GraphCollection -Uri "https://graph.microsoft.com/v1.0/applications?`$filter=$escapedId"
    } else {
        $escapedName = [Uri]::EscapeDataString("displayName eq '$($GatewayDisplayName.Replace("'", "''"))'")
        $matches = Get-GraphCollection -Uri "https://graph.microsoft.com/v1.0/applications?`$filter=$escapedName"
    }
    if ($matches.Count -gt 1) {
        throw "Multiple gateway applications matched. Rerun with -GatewayApplicationClientId."
    }

    $scopeId = [guid]::NewGuid().Guid
    $roleId = if ($GatewayAppRoleValue) { [guid]::NewGuid().Guid } else { $null }
    if ($matches.Count -eq 0) {
        $newBody = @{
            displayName = $GatewayDisplayName
            signInAudience = 'AzureADMyOrg'
            groupMembershipClaims = 'SecurityGroup'
            api = @{
                requestedAccessTokenVersion = 2
                oauth2PermissionScopes = @(@{
                    id = $scopeId
                    value = $GatewayScopeValue
                    type = 'User'
                    isEnabled = $true
                    adminConsentDisplayName = "Access $GatewayDisplayName"
                    adminConsentDescription = "Allow a signed-in user to access $GatewayDisplayName."
                    userConsentDisplayName = "Access $GatewayDisplayName"
                    userConsentDescription = "Allow this client to access $GatewayDisplayName on your behalf."
                })
            }
            appRoles = @()
        }
        if ($GatewayAppRoleValue) {
            $newBody.appRoles = @(@{
                id = $roleId
                value = $GatewayAppRoleValue
                displayName = 'MCP gateway access'
                description = 'Permits assigned users or groups to access the MCP gateway.'
                allowedMemberTypes = @('User')
                isEnabled = $true
            })
        }
        $application = Invoke-GraphMutation -Target $GatewayDisplayName -Action 'Create single-tenant MCP gateway application' -Method POST -Uri 'https://graph.microsoft.com/v1.0/applications' -Body $newBody
        if (-not $application) {
            return [pscustomobject]@{ id = '(planned)'; appId = '(planned-gateway-client-id)'; ScopeId = $scopeId; RoleId = $roleId }
        }
    } else {
        $application = $matches[0]
        if ($application.signInAudience -ne 'AzureADMyOrg') {
            throw "Existing gateway application '$($application.appId)' is not single-tenant (AzureADMyOrg). Refusing to weaken the tenant boundary."
        }
        $existingScopes = @($application.api.oauth2PermissionScopes)
        $scope = $existingScopes | Where-Object { $_.value -eq $GatewayScopeValue } | Select-Object -First 1
        $updatedScopes = @($existingScopes)
        if (-not $scope) {
            $updatedScopes += @{
                id = $scopeId; value = $GatewayScopeValue; type = 'User'; isEnabled = $true
                adminConsentDisplayName = "Access $GatewayDisplayName"
                adminConsentDescription = "Allow a signed-in user to access $GatewayDisplayName."
                userConsentDisplayName = "Access $GatewayDisplayName"
                userConsentDescription = "Allow this client to access $GatewayDisplayName on your behalf."
            }
        } else { $scopeId = $scope.id }

        $existingRoles = @($application.appRoles)
        $role = if ($GatewayAppRoleValue) { $existingRoles | Where-Object { $_.value -eq $GatewayAppRoleValue } | Select-Object -First 1 } else { $null }
        $updatedRoles = @($existingRoles)
        if ($GatewayAppRoleValue -and -not $role) {
            $updatedRoles += @{
                id = $roleId; value = $GatewayAppRoleValue; displayName = 'MCP gateway access'
                description = 'Permits assigned users or groups to access the MCP gateway.'
                allowedMemberTypes = @('User'); isEnabled = $true
            }
        } elseif ($role) { $roleId = $role.id }

        $patchBody = @{
            groupMembershipClaims = 'SecurityGroup'
            api = @{ requestedAccessTokenVersion = 2; oauth2PermissionScopes = $updatedScopes }
            appRoles = $updatedRoles
        }
        [void](Invoke-GraphMutation -Target $application.appId -Action 'Ensure gateway scope, roles, v2 tokens, and security-group claims' -Method PATCH -Uri "https://graph.microsoft.com/v1.0/applications/$($application.id)" -Body $patchBody)
    }

    $identifierUri = "api://$($application.appId)"
    if (@($application.identifierUris) -notcontains $identifierUri) {
        $uris = @($application.identifierUris) + $identifierUri
        [void](Invoke-GraphMutation -Target $application.appId -Action "Add identifier URI $identifierUri" -Method PATCH -Uri "https://graph.microsoft.com/v1.0/applications/$($application.id)" -Body @{ identifierUris = $uris })
    }
    Add-Member -InputObject $application -NotePropertyName ScopeId -NotePropertyValue $scopeId -Force
    Add-Member -InputObject $application -NotePropertyName RoleId -NotePropertyValue $roleId -Force
    return $application
}

function Ensure-ServicePrincipal {
    param([Parameter(Mandatory)][psobject] $Application, [switch] $AssignmentRequired)
    if ($Application.appId -like '(planned*') {
        return [pscustomobject]@{ id = '(planned)'; appId = $Application.appId; appRoleAssignmentRequired = $AssignmentRequired.IsPresent }
    }
    $filter = [Uri]::EscapeDataString("appId eq '$($Application.appId)'")
    $matches = Get-GraphCollection -Uri "https://graph.microsoft.com/v1.0/servicePrincipals?`$filter=$filter"
    if ($matches.Count -eq 0) {
        $sp = Invoke-GraphMutation -Target $Application.appId -Action 'Create enterprise application/service principal' -Method POST -Uri 'https://graph.microsoft.com/v1.0/servicePrincipals' -Body @{ appId = $Application.appId }
        if (-not $sp) { return [pscustomobject]@{ id = '(planned)'; appId = $Application.appId; appRoleAssignmentRequired = $false } }
    } elseif ($matches.Count -eq 1) {
        $sp = $matches[0]
    } else {
        throw "Multiple service principals matched appId '$($Application.appId)'."
    }
    if ($AssignmentRequired -and -not $sp.appRoleAssignmentRequired) {
        [void](Invoke-GraphMutation -Target $sp.id -Action 'Require user/group assignment for gateway access' -Method PATCH -Uri "https://graph.microsoft.com/v1.0/servicePrincipals/$($sp.id)" -Body @{ appRoleAssignmentRequired = $true })
        $sp.appRoleAssignmentRequired = $true
    }
    return $sp
}

function Ensure-GraphAppRoleAssignment {
    param(
        [Parameter(Mandatory)][string] $PrincipalObjectId,
        [Parameter(Mandatory)][string] $ResourceServicePrincipalId,
        [Parameter(Mandatory)][string] $AppRoleId,
        [Parameter(Mandatory)][string] $RoleValue
    )
    if ($PrincipalObjectId -like '(planned*') { return }
    $existing = Get-GraphCollection -Uri "https://graph.microsoft.com/v1.0/servicePrincipals/$PrincipalObjectId/appRoleAssignments"
    $match = $existing | Where-Object { $_.resourceId -eq $ResourceServicePrincipalId -and $_.appRoleId -eq $AppRoleId }
    if ($match) {
        Write-Verbose "Graph role '$RoleValue' is already assigned to '$PrincipalObjectId'."
        return
    }
    [void](Invoke-GraphMutation -Target $PrincipalObjectId -Action "Grant Microsoft Graph application permission $RoleValue" -Method POST -Uri "https://graph.microsoft.com/v1.0/servicePrincipals/$PrincipalObjectId/appRoleAssignments" -Body @{
        principalId = $PrincipalObjectId
        resourceId = $ResourceServicePrincipalId
        appRoleId = $AppRoleId
    })
}

function Ensure-FederatedCredential {
    param([Parameter(Mandatory)][psobject] $Application)
    $name = "github-$GitHubEnvironment"
    $issuer = 'https://token.actions.githubusercontent.com'
    $subject = "repo:$GitHubOwner/${GitHubRepository}:environment:$GitHubEnvironment"
    if ($Application.id -eq '(planned)') { return }
    $existing = Get-GraphCollection -Uri "https://graph.microsoft.com/v1.0/applications/$($Application.id)/federatedIdentityCredentials"
    $credential = $existing | Where-Object { $_.name -eq $name } | Select-Object -First 1
    if ($credential) {
        if ($credential.issuer -ne $issuer -or $credential.subject -ne $subject -or @($credential.audiences) -notcontains 'api://AzureADTokenExchange') {
            throw "Federated credential '$name' exists with different trust settings. Review it manually; JANUS will not broaden or replace federation silently."
        }
        return
    }
    [void](Invoke-GraphMutation -Target $Application.appId -Action "Trust GitHub Environment subject $subject" -Method POST -Uri "https://graph.microsoft.com/v1.0/applications/$($Application.id)/federatedIdentityCredentials" -Body @{
        name = $name
        issuer = $issuer
        subject = $subject
        audiences = @('api://AzureADTokenExchange')
        description = 'JANUS GitHub Actions protected environment deployment identity'
    })
}

Write-Host 'JANUS tenant bootstrap' -ForegroundColor Green
Write-Host 'No JANUS deployment is performed. All mutations require ShouldProcess confirmation.' -ForegroundColor Yellow
Assert-Prerequisites

Write-Step 'Validating Azure tenant and subscription context'
$account = Invoke-AzJson -Arguments @('account', 'show', '--subscription', $SubscriptionId)
if ([string]$account.id -ne $SubscriptionId) { throw "Azure CLI returned subscription '$($account.id)', expected '$SubscriptionId'." }
if ([string]$account.tenantId -ne $TenantId) { throw "Subscription belongs to tenant '$($account.tenantId)', expected '$TenantId'." }
if ([string]$account.state -ne 'Enabled') { throw "Subscription '$SubscriptionId' is not enabled." }

$graphScopes = @('Application.Read.All', 'Application.ReadWrite.OwnedBy', 'AppRoleAssignment.ReadWrite.All')
if ($GatewayAccessGroupObjectId) { $graphScopes += 'Group.Read.All' }
Connect-MgGraph -TenantId $TenantId -Scopes $graphScopes -NoWelcome
$graphContext = Get-MgContext
if (-not $graphContext -or $graphContext.TenantId -ne $TenantId) { throw 'Microsoft Graph authenticated tenant does not match -TenantId.' }

try {
    Write-Step "Ensuring resource group '$ResourceGroup'"
    $resourceGroupExists = [bool](Invoke-AzJson -Arguments @('group', 'exists', '--name', $ResourceGroup, '--subscription', $SubscriptionId))
    if (-not $resourceGroupExists) {
        $createdRg = Invoke-AzMutation -Target $ResourceGroup -Action "Create resource group in $Location" -Arguments @('group', 'create', '--name', $ResourceGroup, '--location', $Location, '--subscription', $SubscriptionId)
        $resourceGroupExists = $null -ne $createdRg
    }
    $resourceGroupScope = "/subscriptions/$SubscriptionId/resourceGroups/$ResourceGroup"

    Write-Step "Ensuring runtime managed identity '$ManagedIdentityName'"
    $managedIdentity = if ($resourceGroupExists) {
        Invoke-AzJson -Arguments @('identity', 'show', '--name', $ManagedIdentityName, '--resource-group', $ResourceGroup, '--subscription', $SubscriptionId) -AllowFailure
    } else { $null }
    if (-not $managedIdentity) {
        $managedIdentity = Invoke-AzMutation -Target $ManagedIdentityName -Action 'Create JANUS runtime user-assigned managed identity' -Arguments @(
            'identity', 'create', '--name', $ManagedIdentityName, '--resource-group', $ResourceGroup,
            '--location', $Location, '--subscription', $SubscriptionId
        )
    }
    if (-not $managedIdentity) {
        $managedIdentity = [pscustomobject]@{ id = '(planned-runtime-identity-resource-id)'; clientId = '(planned-runtime-client-id)'; principalId = '(planned-runtime-principal-id)' }
    }

    Write-Step "Ensuring Azure Container Registry '$AcrName'"
    $registry = if ($resourceGroupExists) {
        Invoke-AzJson -Arguments @('acr', 'show', '--name', $AcrName, '--resource-group', $ResourceGroup, '--subscription', $SubscriptionId) -AllowFailure
    } else { $null }
    if (-not $registry) {
        $registry = Invoke-AzMutation -Target $AcrName -Action 'Create Basic ACR with admin credentials disabled' -Arguments @(
            'acr', 'create', '--name', $AcrName, '--resource-group', $ResourceGroup,
            '--location', $Location, '--sku', 'Basic', '--admin-enabled', 'false', '--subscription', $SubscriptionId
        )
    }
    if (-not $registry) { $registry = [pscustomobject]@{ id = '(planned-acr-resource-id)'; loginServer = "$AcrName.azurecr.io" } }

    if ($managedIdentity.principalId -notlike '(planned*' -and $registry.id -notlike '(planned*') {
        Ensure-AzureRoleAssignment -PrincipalObjectId $managedIdentity.principalId -PrincipalType ServicePrincipal -Role AcrPull -Scope $registry.id
    }

    Write-Step "Granting the runtime identity Microsoft Graph Application.ReadWrite.OwnedBy"
    $graphSpFilter = [Uri]::EscapeDataString("appId eq '00000003-0000-0000-c000-000000000000'")
    $graphSp = (Get-GraphCollection -Uri "https://graph.microsoft.com/v1.0/servicePrincipals?`$filter=$graphSpFilter")[0]
    if (-not $graphSp) { throw 'Microsoft Graph service principal was not found in this tenant.' }
    $ownedByRole = @($graphSp.appRoles) | Where-Object { $_.value -eq 'Application.ReadWrite.OwnedBy' -and $_.allowedMemberTypes -contains 'Application' } | Select-Object -First 1
    if (-not $ownedByRole) { throw 'Microsoft Graph Application.ReadWrite.OwnedBy application role was not found.' }
    Ensure-GraphAppRoleAssignment -PrincipalObjectId $managedIdentity.principalId -ResourceServicePrincipalId $graphSp.id -AppRoleId $ownedByRole.id -RoleValue $ownedByRole.value

    $gatewayApp = Ensure-GatewayApplication
    $gatewaySp = Ensure-ServicePrincipal -Application $gatewayApp -AssignmentRequired
    $gatewayResourceUri = "api://$($gatewayApp.appId)"
    $gatewayScope = "$gatewayResourceUri/$GatewayScopeValue"
    $gatewayScopeMapping = "$gatewayScope=$($gatewayApp.ScopeId)"

    if ($GatewayAccessGroupObjectId) {
        if (-not $GatewayAppRoleValue) { throw '-GatewayAccessGroupObjectId requires -GatewayAppRoleValue.' }
        if ($gatewaySp.id -notlike '(planned*') {
            $assigned = Get-GraphCollection -Uri "https://graph.microsoft.com/v1.0/servicePrincipals/$($gatewaySp.id)/appRoleAssignedTo"
            $groupAssignment = $assigned | Where-Object { $_.principalId -eq $GatewayAccessGroupObjectId -and $_.appRoleId -eq $gatewayApp.RoleId }
            if (-not $groupAssignment) {
                [void](Invoke-GraphMutation -Target $GatewayAccessGroupObjectId -Action "Assign group to gateway app role $GatewayAppRoleValue" -Method POST -Uri "https://graph.microsoft.com/v1.0/servicePrincipals/$($gatewaySp.id)/appRoleAssignedTo" -Body @{
                    principalId = $GatewayAccessGroupObjectId
                    resourceId = $gatewaySp.id
                    appRoleId = $gatewayApp.RoleId
                })
            }
        }
    }

    Write-Step "Ensuring GitHub OIDC deployment identity '$DeploymentIdentityDisplayName'"
    $deploymentFilter = [Uri]::EscapeDataString("displayName eq '$($DeploymentIdentityDisplayName.Replace("'", "''"))'")
    $deploymentApps = Get-GraphCollection -Uri "https://graph.microsoft.com/v1.0/applications?`$filter=$deploymentFilter"
    if ($deploymentApps.Count -gt 1) { throw 'Multiple deployment applications matched. Rename duplicates before rerunning.' }
    if ($deploymentApps.Count -eq 0) {
        $deploymentApp = Invoke-GraphMutation -Target $DeploymentIdentityDisplayName -Action 'Create single-tenant GitHub deployment application' -Method POST -Uri 'https://graph.microsoft.com/v1.0/applications' -Body @{
            displayName = $DeploymentIdentityDisplayName
            signInAudience = 'AzureADMyOrg'
        }
        if (-not $deploymentApp) { $deploymentApp = [pscustomobject]@{ id = '(planned)'; appId = '(planned-deployment-client-id)' } }
    } else {
        $deploymentApp = $deploymentApps[0]
        if ($deploymentApp.signInAudience -ne 'AzureADMyOrg') { throw 'Existing deployment application is not single-tenant.' }
    }
    $deploymentSp = Ensure-ServicePrincipal -Application $deploymentApp
    Ensure-FederatedCredential -Application $deploymentApp

    if ($deploymentSp.id -notlike '(planned*' -and $resourceGroupExists) {
        # Contributor is intentionally bounded to the JANUS resource group. It
        # cannot grant RBAC; bootstrap retains responsibility for role grants.
        Ensure-AzureRoleAssignment -PrincipalObjectId $deploymentSp.id -PrincipalType ServicePrincipal -Role Contributor -Scope $resourceGroupScope
        if ($registry.id -notlike '(planned*') {
            Ensure-AzureRoleAssignment -PrincipalObjectId $deploymentSp.id -PrincipalType ServicePrincipal -Role AcrPush -Scope $registry.id
        }
    }

    $result = [ordered]@{
        schemaVersion = 1
        generatedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
        dryRun = [bool]$WhatIfPreference
        githubEnvironment = $GitHubEnvironment
        githubVariables = [ordered]@{
            AZURE_TENANT_ID = $TenantId
            AZURE_SUBSCRIPTION_ID = $SubscriptionId
            AZURE_DEPLOYMENT_CLIENT_ID = $deploymentApp.appId
            AZURE_RESOURCE_GROUP = $ResourceGroup
            AZURE_LOCATION = $Location
            JANUS_ENVIRONMENT_NAME = $EnvironmentName
            JANUS_GATEWAY_CLIENT_ID = $gatewayApp.appId
            JANUS_GATEWAY_RESOURCE_URI = $gatewayResourceUri
            JANUS_ALLOWED_GATEWAY_SCOPES = $gatewayScopeMapping
            JANUS_RUNTIME_MI_CLIENT_ID = $managedIdentity.clientId
            JANUS_RUNTIME_MI_RESOURCE_ID = $managedIdentity.id
            ACR_NAME = $AcrName
            JANUS_CLEANUP_RETENTION_DAYS = $CleanupRetentionDays
            JANUS_CLEANUP_DRY_RUN = 'true'
            JANUS_EXTERNAL_INGRESS_ENABLED = 'false'
        }
        githubEnvironmentSecrets = @(
            'KEYCLOAK_ADMIN_PASSWORD'
            'KEYCLOAK_POSTGRES_PASSWORD'
        )
        identity = [ordered]@{
            runtimeManagedIdentityPrincipalId = $managedIdentity.principalId
            runtimeGraphApplicationPermission = 'Application.ReadWrite.OwnedBy'
            deploymentServicePrincipalObjectId = $deploymentSp.id
            deploymentAzureRoles = @(
                @{ role = 'Contributor'; scope = $resourceGroupScope }
                @{ role = 'AcrPush'; scope = $registry.id }
            )
            githubFederatedSubject = "repo:$GitHubOwner/${GitHubRepository}:environment:$GitHubEnvironment"
        }
        gateway = [ordered]@{
            applicationObjectId = $gatewayApp.id
            servicePrincipalObjectId = $gatewaySp.id
            assignmentRequired = $true
            groupMembershipClaims = 'SecurityGroup'
            delegatedScope = $gatewayScope
            delegatedScopePermissionId = $gatewayApp.ScopeId
            janusScopeMapping = $gatewayScopeMapping
            appRole = $GatewayAppRoleValue
            assignedGroupObjectId = $GatewayAccessGroupObjectId
        }
        nextSteps = @(
            "Create a protected GitHub Environment named '$GitHubEnvironment' with required reviewers."
            'Add githubVariables as repository or Environment variables.'
            'Add the two named Environment secrets using high-entropy independent values.'
            'After deployment, create a bounded, expiring Keycloak initial access token and distribute it only to approved MCP client operators.'
            'Run the Build and Deploy JANUS workflow. Cleanup remains dry-run and public ingress remains disabled by default.'
        )
    }

    $json = $result | ConvertTo-Json -Depth 12
    Write-Step 'Bootstrap outputs (contains identifiers only; no credentials)'
    Write-Host $json
    if ($OutputJsonPath) {
        $fullOutputPath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputJsonPath)
        if ($PSCmdlet.ShouldProcess($fullOutputPath, 'Write bootstrap output JSON')) {
            $json | Set-Content -LiteralPath $fullOutputPath -Encoding utf8NoBOM
            Write-Host "Wrote $fullOutputPath" -ForegroundColor Green
        }
    }
} finally {
    Disconnect-MgGraph -ErrorAction SilentlyContinue | Out-Null
}
