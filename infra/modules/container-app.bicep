@description('Azure region for the Container App.')
param location string

@description('Container App name.')
param appName string

@description('Container Apps managed environment resource ID.')
param environmentId string

@description('Container Apps environment default domain.')
param environmentDefaultDomain string

@description('Immutable container image reference, preferably registry/repository@sha256:digest.')
param containerImage string

@description('Existing Azure Container Registry login server.')
param registryLoginServer string

@description('User-assigned managed identity resource ID.')
param managedIdentityResourceId string

@description('User-assigned managed identity client ID.')
param managedIdentityClientId string

@description('Permitted Entra tenant ID.')
param tenantId string

@description('MCP gateway Entra application client ID.')
param gatewayApplicationClientId string

@description('MCP gateway Application ID URI.')
param gatewayResourceUri string

@description('Comma-separated scope-uri=permission-uuid mappings permitted during registration.')
param allowedGatewayScopesCsv string

@description('Comma-separated redirect URI policy entries.')
param allowedRedirectPatterns string

@description('Maximum redirect URIs in a registration request.')
param maxRedirectUris int

@description('Maximum metadata request bytes accepted by JANUS.')
param maxRequestBodyBytes int

@description('Maximum accepted client-name length.')
param maxClientNameLength int

@description('Maximum accepted metadata field length.')
param maxFieldLength int

@description('Per-source registrations allowed per minute in this replica.')
param sourceRatePerMinute int

@description('Global registrations allowed per minute in this replica.')
param globalRatePerMinute int

@description('Maximum successful registrations during this process lifetime.')
param maxRegistrationsPerProcess int

@description('Idempotency result lifetime in seconds.')
param idempotencyTtlSeconds int

@description('PostgreSQL server FQDN.')
param databaseServerFqdn string

@description('PostgreSQL database name.')
param databaseName string

@description('PostgreSQL administrator login name.')
param databaseUsername string

@secure()
@description('PostgreSQL administrator password.')
param databasePassword string

@description('Keycloak bootstrap administrator username.')
param keycloakAdminUsername string

@secure()
@description('Keycloak bootstrap administrator password.')
param keycloakAdminPassword string

@description('Expose the DCR endpoint through public Container Apps ingress. Defaults off; enable only with admission controls and an edge rate limiter where required.')
param externalIngressEnabled bool = false

@description('Optional source CIDRs allowed by Container Apps ingress. An empty list does not add an IP allowlist.')
param allowedIngressCidrs array = []

@description('Minimum replicas.')
@minValue(1)
param minReplicas int = 1

@description('Maximum replicas.')
@minValue(1)
param maxReplicas int = 3

@description('Resource tags.')
param tags object = {}

var appHostname = '${appName}.${environmentDefaultDomain}'
var ingressBase = {
  external: externalIngressEnabled
  targetPort: 8080
  transport: 'http'
  allowInsecure: false
  clientCertificateMode: 'ignore'
  traffic: [
    {
      latestRevision: true
      weight: 100
    }
  ]
}
var ipSecurityRestrictions = [for (cidr, index) in allowedIngressCidrs: {
  name: 'allow-${index}'
  ipAddressRange: cidr
  action: 'Allow'
}]
var ingress = union(ingressBase, length(allowedIngressCidrs) > 0 ? {
  ipSecurityRestrictions: ipSecurityRestrictions
} : {})

resource app 'Microsoft.App/containerApps@2024-03-01' = {
  name: appName
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
    managedEnvironmentId: environmentId
    workloadProfileName: 'Consumption'
    configuration: {
      activeRevisionsMode: 'Single'
      ingress: ingress
      registries: [
        {
          server: registryLoginServer
          identity: managedIdentityResourceId
        }
      ]
      secrets: [
        {
          name: 'postgres-password'
          value: databasePassword
        }
        {
          name: 'keycloak-bootstrap-password'
          value: keycloakAdminPassword
        }
      ]
    }
    template: {
      containers: [
        {
          name: 'janus'
          image: containerImage
          resources: {
            cpu: json('1.0')
            memory: '2Gi'
          }
          env: [
            { name: 'KC_BOOTSTRAP_ADMIN_USERNAME', value: keycloakAdminUsername }
            { name: 'KC_BOOTSTRAP_ADMIN_PASSWORD', secretRef: 'keycloak-bootstrap-password' }
            { name: 'KC_DB', value: 'postgres' }
            { name: 'KC_DB_URL', value: 'jdbc:postgresql://${databaseServerFqdn}:5432/${databaseName}?sslmode=require' }
            { name: 'KC_DB_USERNAME', value: databaseUsername }
            { name: 'KC_DB_PASSWORD', secretRef: 'postgres-password' }
            { name: 'KC_HTTP_ENABLED', value: 'true' }
            { name: 'KC_HOSTNAME', value: 'https://${appHostname}' }
            { name: 'KC_HOSTNAME_STRICT', value: 'true' }
            { name: 'KC_PROXY_HEADERS', value: 'xforwarded' }
            { name: 'KC_HEALTH_ENABLED', value: 'true' }
            { name: 'KC_METRICS_ENABLED', value: 'true' }
            { name: 'KC_HTTP_MANAGEMENT_HEALTH_ENABLED', value: 'true' }
            { name: 'JANUS_TENANT_ID', value: tenantId }
            { name: 'JANUS_GATEWAY_CLIENT_ID', value: gatewayApplicationClientId }
            { name: 'JANUS_GATEWAY_RESOURCE_URI', value: gatewayResourceUri }
            { name: 'JANUS_ALLOWED_GATEWAY_SCOPES', value: allowedGatewayScopesCsv }
            { name: 'JANUS_ALLOWED_REDIRECT_URI_PATTERNS', value: allowedRedirectPatterns }
            { name: 'JANUS_MAX_REDIRECT_URIS', value: string(maxRedirectUris) }
            { name: 'JANUS_MAX_REQUEST_BODY_BYTES', value: string(maxRequestBodyBytes) }
            { name: 'JANUS_MAX_CLIENT_NAME_LENGTH', value: string(maxClientNameLength) }
            { name: 'JANUS_MAX_FIELD_LENGTH', value: string(maxFieldLength) }
            { name: 'JANUS_SOURCE_RATE_PER_MINUTE', value: string(sourceRatePerMinute) }
            { name: 'JANUS_GLOBAL_RATE_PER_MINUTE', value: string(globalRatePerMinute) }
            { name: 'JANUS_MAX_REGISTRATIONS_PER_PROCESS', value: string(maxRegistrationsPerProcess) }
            { name: 'JANUS_IDEMPOTENCY_TTL_SECONDS', value: string(idempotencyTtlSeconds) }
            { name: 'JANUS_ADMISSION_MODE', value: 'initial-access-token' }
            { name: 'AZURE_CLIENT_ID', value: managedIdentityClientId }
          ]
          probes: [
            {
              type: 'Startup'
              httpGet: {
                path: '/health/started'
                port: 9000
                scheme: 'HTTP'
              }
              initialDelaySeconds: 20
              periodSeconds: 10
              failureThreshold: 18
            }
            {
              type: 'Readiness'
              httpGet: {
                path: '/health/ready'
                port: 9000
                scheme: 'HTTP'
              }
              initialDelaySeconds: 10
              periodSeconds: 10
              failureThreshold: 6
            }
            {
              type: 'Liveness'
              httpGet: {
                path: '/health/live'
                port: 9000
                scheme: 'HTTP'
              }
              initialDelaySeconds: 60
              periodSeconds: 30
              failureThreshold: 3
            }
          ]
        }
      ]
      scale: {
        minReplicas: minReplicas
        maxReplicas: maxReplicas
        rules: [
          {
            name: 'http-concurrency'
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

output appId string = app.id
output appName string = app.name
output appFqdn string = app.properties.configuration.ingress.fqdn
output dcrEndpoint string = 'https://${app.properties.configuration.ingress.fqdn}/realms/janus/clients-registrations/openid-connect'
