targetScope = 'resourceGroup'

@description('Azure region for all JANUS resources.')
param location string = resourceGroup().location

@description('Short, lowercase environment name used in resource names.')
@minLength(3)
@maxLength(20)
param environmentName string

@description('Permitted Microsoft Entra tenant ID.')
param tenantId string

@description('MCP gateway Entra application client ID.')
param gatewayApplicationClientId string

@description('MCP gateway Application ID URI, for example api://<client-id>.')
param gatewayResourceUri string

@description('Comma-separated scope-uri=permission-uuid mappings JANUS may place on generated clients.')
param allowedGatewayScopesCsv string

@description('Existing Azure Container Registry name. Bootstrap creates it before the workflow builds an image.')
param acrName string

@description('Immutable JANUS image reference. Production deployment should use an ACR digest reference.')
param containerImage string

@description('Existing JANUS runtime user-assigned managed identity client ID.')
param managedIdentityClientId string

@description('Existing JANUS runtime user-assigned managed identity resource ID.')
param managedIdentityResourceId string

@secure()
@description('Keycloak bootstrap administrator password. Supply through a protected GitHub Environment secret.')
param keycloakAdminPassword string

@secure()
@description('PostgreSQL administrator password. Supply through a protected GitHub Environment secret.')
param postgresAdministratorPassword string

@description('Keycloak bootstrap administrator username.')
param keycloakAdminUsername string = 'janus-bootstrap-admin'

@description('PostgreSQL administrator username.')
param postgresAdministratorLogin string = 'janusadmin'

@description('PostgreSQL SKU name.')
param postgresSkuName string = 'Standard_B1ms'

@description('PostgreSQL SKU tier.')
@allowed([
  'Burstable'
  'GeneralPurpose'
  'MemoryOptimized'
])
param postgresSkuTier string = 'Burstable'

@description('Enable PostgreSQL high availability. Select a compatible General Purpose or Memory Optimized SKU.')
param postgresHighAvailability bool = false

@description('Use existing delegated subnet and private DNS resources.')
param useExistingNetwork bool = false

@description('Existing Container Apps infrastructure subnet resource ID when useExistingNetwork is true.')
param existingContainerAppsSubnetId string = ''

@description('Existing PostgreSQL delegated subnet resource ID when useExistingNetwork is true.')
param existingPostgresSubnetId string = ''

@description('Existing PostgreSQL private DNS zone resource ID when useExistingNetwork is true.')
param existingPostgresPrivateDnsZoneId string = ''

@description('Expose JANUS using public Container Apps ingress. Defaults false; enable only with admission controls and edge abuse protection.')
param externalIngressEnabled bool = false

@description('Optional CIDRs permitted by Container Apps ingress.')
param allowedIngressCidrs array = []

@description('Comma-separated redirect URI policy entries. Keep compatibility exceptions explicit.')
param allowedRedirectPatterns string = 'http://localhost:{port}/*,http://127.0.0.1:{port}/*,http://[::1]:{port}/*'

@description('Maximum redirect URIs per registration.')
@minValue(1)
@maxValue(20)
param maxRedirectUris int = 10

@description('Maximum DCR request bytes.')
@minValue(1024)
@maxValue(65536)
param maxRequestBodyBytes int = 16384

@description('Maximum client-name length.')
@minValue(1)
param maxClientNameLength int = 64

@description('Maximum metadata field length.')
@minValue(1)
param maxFieldLength int = 512

@description('Per-source registrations allowed per minute in a replica.')
@minValue(1)
param sourceRatePerMinute int = 5

@description('Global registrations allowed per minute in a replica.')
@minValue(1)
param globalRatePerMinute int = 25

@description('Maximum successful registrations per process lifetime.')
@minValue(1)
param maxRegistrationsPerProcess int = 1000

@description('Idempotency result lifetime in seconds.')
@minValue(1)
param idempotencyTtlSeconds int = 600

@description('Cleanup schedule in five-field UTC cron syntax.')
param cleanupCronExpression string = '0 2 * * *'

@description('Cleanup retention in days.')
@minValue(1)
param cleanupRetentionDays int = 30

@description('Keep cleanup non-destructive until lifecycle evidence and logs have been reviewed.')
param cleanupDryRun bool = true

@description('Maximum number of applications deleted by one cleanup run.')
@minValue(1)
param cleanupMaxDeletesPerRun int = 20

@description('Enable zone redundancy for the Container Apps environment where supported.')
param containerEnvironmentZoneRedundant bool = false

@description('Resource tags added to all resources created by this deployment.')
param tags object = {
  application: 'janus'
  managedBy: 'bicep'
  dataPlaneIssuer: 'microsoft-entra-id'
}

var baseName = 'janus-${environmentName}'
var postgresServerName = take('${replace(baseName, '-', '')}${uniqueString(subscription().id, resourceGroup().id)}', 63)

module observability './modules/observability.bicep' = {
  name: 'observability'
  params: {
    location: location
    workspaceName: '${baseName}-law'
    retentionInDays: 30
    tags: tags
  }
}

module network './modules/network.bicep' = {
  name: 'network'
  params: {
    location: location
    baseName: baseName
    useExistingNetwork: useExistingNetwork
    existingContainerAppsSubnetId: existingContainerAppsSubnetId
    existingPostgresSubnetId: existingPostgresSubnetId
    existingPostgresPrivateDnsZoneId: existingPostgresPrivateDnsZoneId
    tags: tags
  }
}

module containerEnvironment './modules/container-environment.bicep' = {
  name: 'container-environment'
  params: {
    location: location
    environmentName: '${baseName}-cae'
    infrastructureSubnetId: network.outputs.containerAppsSubnetId
    logAnalyticsWorkspaceName: observability.outputs.workspaceName
    zoneRedundant: containerEnvironmentZoneRedundant
    tags: tags
  }
}

module database './modules/database.bicep' = {
  name: 'keycloak-database'
  params: {
    location: location
    serverName: postgresServerName
    databaseName: 'keycloak'
    administratorLogin: postgresAdministratorLogin
    administratorPassword: postgresAdministratorPassword
    delegatedSubnetId: network.outputs.postgresSubnetId
    privateDnsZoneId: network.outputs.postgresPrivateDnsZoneId
    skuName: postgresSkuName
    skuTier: postgresSkuTier
    highAvailability: postgresHighAvailability
    backupRetentionDays: 7
    tags: tags
  }
}

module registry './modules/registry.bicep' = {
  name: 'registry-diagnostics'
  params: {
    registryName: acrName
    logAnalyticsWorkspaceId: observability.outputs.workspaceId
  }
}

module janusApp './modules/container-app.bicep' = {
  name: 'janus-app'
  params: {
    location: location
    appName: '${baseName}-app'
    environmentId: containerEnvironment.outputs.environmentId
    environmentDefaultDomain: containerEnvironment.outputs.defaultDomain
    containerImage: containerImage
    registryLoginServer: registry.outputs.loginServer
    managedIdentityResourceId: managedIdentityResourceId
    managedIdentityClientId: managedIdentityClientId
    tenantId: tenantId
    gatewayApplicationClientId: gatewayApplicationClientId
    gatewayResourceUri: gatewayResourceUri
    allowedGatewayScopesCsv: allowedGatewayScopesCsv
    allowedRedirectPatterns: allowedRedirectPatterns
    maxRedirectUris: maxRedirectUris
    maxRequestBodyBytes: maxRequestBodyBytes
    maxClientNameLength: maxClientNameLength
    maxFieldLength: maxFieldLength
    sourceRatePerMinute: sourceRatePerMinute
    globalRatePerMinute: globalRatePerMinute
    maxRegistrationsPerProcess: maxRegistrationsPerProcess
    idempotencyTtlSeconds: idempotencyTtlSeconds
    databaseServerFqdn: database.outputs.serverFqdn
    databaseName: database.outputs.databaseName
    databaseUsername: postgresAdministratorLogin
    databasePassword: postgresAdministratorPassword
    keycloakAdminUsername: keycloakAdminUsername
    keycloakAdminPassword: keycloakAdminPassword
    externalIngressEnabled: externalIngressEnabled
    allowedIngressCidrs: allowedIngressCidrs
    minReplicas: 1
    // Per-process creation/idempotency controls are intentionally not treated
    // as distributed controls. Raise only after adding reviewed edge admission.
    maxReplicas: 1
    tags: tags
  }
}

module cleanupJob './modules/cleanup-job.bicep' = {
  name: 'cleanup-job'
  params: {
    location: location
    jobName: '${baseName}-cleanup'
    environmentId: containerEnvironment.outputs.environmentId
    containerImage: containerImage
    registryLoginServer: registry.outputs.loginServer
    managedIdentityResourceId: managedIdentityResourceId
    managedIdentityClientId: managedIdentityClientId
    tenantId: tenantId
    cronExpression: cleanupCronExpression
    retentionDays: cleanupRetentionDays
    dryRun: cleanupDryRun
    maxDeletesPerRun: cleanupMaxDeletesPerRun
    tags: tags
  }
}

output janusContainerAppId string = janusApp.outputs.appId
output janusFqdn string = janusApp.outputs.appFqdn
output dcrEndpoint string = janusApp.outputs.dcrEndpoint
output cleanupJobId string = cleanupJob.outputs.jobId
output runtimeManagedIdentityResourceId string = managedIdentityResourceId
output logAnalyticsWorkspaceId string = observability.outputs.workspaceId
output postgresServerId string = database.outputs.serverId
