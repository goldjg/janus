@description('Azure region for the Container Apps Job.')
param location string

@description('Container Apps Job name.')
param jobName string

@description('Container Apps managed environment resource ID.')
param environmentId string

@description('Immutable container image reference.')
param containerImage string

@description('Azure Container Registry login server.')
param registryLoginServer string

@description('User-assigned managed identity resource ID.')
param managedIdentityResourceId string

@description('User-assigned managed identity client ID.')
param managedIdentityClientId string

@description('Permitted Entra tenant ID.')
param tenantId string

@description('Cleanup schedule in five-field UTC cron syntax.')
param cronExpression string = '0 2 * * *'

@description('Retention period in days since reliable last-use evidence.')
@minValue(1)
param retentionDays int = 30

@description('Run cleanup without deleting registrations. Defaults true and must be explicitly disabled after reviewing lifecycle logs.')
param dryRun bool = true

@description('Maximum applications a single run may attempt to delete.')
@minValue(1)
param maxDeletesPerRun int = 20

@description('Resource tags.')
param tags object = {}

resource job 'Microsoft.App/jobs@2024-03-01' = {
  name: jobName
  location: location
  tags: tags
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: {
      '${managedIdentityResourceId}': {}
    }
  }
  properties: {
    environmentId: environmentId
    workloadProfileName: 'Consumption'
    configuration: {
      triggerType: 'Schedule'
      replicaTimeout: 900
      replicaRetryLimit: 1
      scheduleTriggerConfig: {
        cronExpression: cronExpression
        parallelism: 1
        replicaCompletionCount: 1
      }
      registries: [
        {
          server: registryLoginServer
          identity: managedIdentityResourceId
        }
      ]
    }
    template: {
      containers: [
        {
          name: 'cleanup'
          image: containerImage
          command: [
            '/opt/janus/cleanup.sh'
          ]
          resources: {
            cpu: json('0.25')
            memory: '0.5Gi'
          }
          env: [
            { name: 'JANUS_TENANT_ID', value: tenantId }
            { name: 'JANUS_REALM', value: 'janus' }
            { name: 'JANUS_CLEANUP_RETENTION_DAYS', value: string(retentionDays) }
            { name: 'JANUS_CLEANUP_DRY_RUN', value: string(dryRun) }
            { name: 'JANUS_CLEANUP_MAX_DELETE_COUNT', value: string(maxDeletesPerRun) }
            { name: 'JANUS_CLEANUP_EVIDENCE_MAX_AGE_HOURS', value: '48' }
            { name: 'AZURE_CLIENT_ID', value: managedIdentityClientId }
          ]
        }
      ]
    }
  }
}

output jobId string = job.id
output jobName string = job.name
