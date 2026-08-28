// JANUS Infrastructure — main.bicep
// Deploys the complete JANUS MCP Client Registration Broker on Azure Container Apps.

@description('Location for all resources.')
param location string = resourceGroup().location

@description('Base name prefix used for all resources (e.g. "janus-prod").')
@minLength(3)
@maxLength(20)
param baseName string

@description('Entra tenant ID.')
param tenantId string

@description('App ID URI of the MCP gateway Entra application (e.g. api://<client-id>).')
param gatewayResourceUri string

@description('Azure Container Registry name (must already exist).')
param acrName string

@description('Container image tag to deploy.')
param imageTag string

@description('Keycloak admin username. Should be stored in Key Vault; use a reference.')
@secure()
param keycloakAdminPassword string

@description('Client ID of the user-assigned managed identity (created by bootstrap script).')
param managedIdentityClientId string

@description('Resource ID of the user-assigned managed identity.')
param managedIdentityResourceId string

@description('Comma-separated allowed redirect URI patterns. Defaults to loopback patterns.')
param allowedRedirectPatterns string = 'http://localhost:,http://127.0.0.1:,http://[::1]:'

@description('Maximum redirect URIs per registration.')
param maxRedirectUris int = 10

@description('Log Analytics workspace name.')
param logAnalyticsWorkspaceName string = '${baseName}-law'

// ─── Log Analytics Workspace ─────────────────────────────────────────────────
resource logAnalytics 'Microsoft.OperationalInsights/workspaces@2023-09-01' = {
  name: logAnalyticsWorkspaceName
  location: location
  properties: {
    sku: {
      name: 'PerGB2018'
    }
    retentionInDays: 30
  }
}

// ─── Container Apps Environment ───────────────────────────────────────────────
resource caEnv 'Microsoft.App/managedEnvironments@2024-03-01' = {
  name: '${baseName}-env'
  location: location
  properties: {
    appLogsConfiguration: {
      destination: 'log-analytics'
      logAnalyticsConfiguration: {
        customerId: logAnalytics.properties.customerId
        sharedKey: logAnalytics.listKeys().primarySharedKey
      }
    }
  }
}

// ─── Keycloak Container App ───────────────────────────────────────────────────
resource keycloakApp 'Microsoft.App/containerApps@2024-03-01' = {
  name: '${baseName}-keycloak'
  location: location
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: {
      '${managedIdentityResourceId}': {}
    }
  }
  properties: {
    environmentId: caEnv.id
    configuration: {
      ingress: {
        external: true
        targetPort: 8080
        transport: 'http'
        allowInsecure: false
      }
      secrets: [
        {
          name: 'keycloak-admin-password'
          value: keycloakAdminPassword
        }
      ]
      registries: [
        {
          server: '${acrName}.azurecr.io'
          identity: managedIdentityResourceId
        }
      ]
    }
    template: {
      containers: [
        {
          name: 'keycloak'
          image: '${acrName}.azurecr.io/janus-keycloak:${imageTag}'
          resources: {
            cpu: json('1.0')
            memory: '2Gi'
          }
          env: [
            { name: 'KEYCLOAK_ADMIN', value: 'admin' }
            { name: 'KEYCLOAK_ADMIN_PASSWORD', secretRef: 'keycloak-admin-password' }
            { name: 'KC_PROXY', value: 'edge' }
            { name: 'KC_HTTP_ENABLED', value: 'true' }
            { name: 'KC_HOSTNAME_STRICT', value: 'false' }
            { name: 'JANUS_TENANT_ID', value: tenantId }
            { name: 'JANUS_GATEWAY_RESOURCE_URI', value: gatewayResourceUri }
            { name: 'JANUS_ALLOWED_REDIRECT_URI_PATTERNS', value: allowedRedirectPatterns }
            { name: 'JANUS_MAX_REDIRECT_URIS', value: string(maxRedirectUris) }
            { name: 'AZURE_CLIENT_ID', value: managedIdentityClientId }
          ]
          probes: [
            {
              type: 'Readiness'
              httpGet: {
                path: '/realms/master'
                port: 8080
              }
              initialDelaySeconds: 30
              periodSeconds: 10
              failureThreshold: 6
            }
            {
              type: 'Liveness'
              httpGet: {
                path: '/realms/master'
                port: 8080
              }
              initialDelaySeconds: 60
              periodSeconds: 30
              failureThreshold: 3
            }
          ]
        }
      ]
      scale: {
        minReplicas: 1
        maxReplicas: 3
        rules: [
          {
            name: 'http-scaling'
            http: {
              metadata: {
                concurrentRequests: '20'
              }
            }
          }
        ]
      }
    }
  }
}

// ─── Cleanup Container Apps Job ───────────────────────────────────────────────
resource cleanupJob 'Microsoft.App/jobs@2024-03-01' = {
  name: '${baseName}-cleanup'
  location: location
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: {
      '${managedIdentityResourceId}': {}
    }
  }
  properties: {
    environmentId: caEnv.id
    configuration: {
      triggerType: 'Schedule'
      replicaTimeout: 300
      replicaRetryLimit: 1
      scheduleTriggerConfig: {
        // Daily at 02:00 UTC
        cronExpression: '0 2 * * *'
        parallelism: 1
        replicaCompletionCount: 1
      }
      registries: [
        {
          server: '${acrName}.azurecr.io'
          identity: managedIdentityResourceId
        }
      ]
    }
    template: {
      containers: [
        {
          name: 'cleanup'
          image: '${acrName}.azurecr.io/janus-keycloak:${imageTag}'
          resources: {
            cpu: json('0.25')
            memory: '0.5Gi'
          }
          // Override the Keycloak entrypoint to run the cleanup job
          command: ['/opt/janus/cleanup.sh']
          env: [
            { name: 'JANUS_TENANT_ID', value: tenantId }
            { name: 'JANUS_REALM', value: 'janus' }
            { name: 'AZURE_CLIENT_ID', value: managedIdentityClientId }
          ]
        }
      ]
    }
  }
}

// ─── Outputs ──────────────────────────────────────────────────────────────────
output keycloakFqdn string = keycloakApp.properties.configuration.ingress.fqdn
output keycloakAppId string = keycloakApp.id
output cleanupJobId string = cleanupJob.id
output dcrEndpoint string = 'https://${keycloakApp.properties.configuration.ingress.fqdn}/realms/janus/clients-registrations/openid-connect'
