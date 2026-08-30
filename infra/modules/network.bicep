@description('Azure region for network resources.')
param location string

@description('Base deployment name.')
param baseName string

@description('Use existing subnet and private DNS resources instead of creating a JANUS VNet.')
param useExistingNetwork bool = false

@description('Existing delegated Container Apps infrastructure subnet resource ID.')
param existingContainerAppsSubnetId string = ''

@description('Existing delegated PostgreSQL subnet resource ID.')
param existingPostgresSubnetId string = ''

@description('Existing PostgreSQL private DNS zone resource ID.')
param existingPostgresPrivateDnsZoneId string = ''

@description('Resource tags.')
param tags object = {}

var virtualNetworkName = '${baseName}-vnet'
var privateDnsZoneName = 'private.postgres.database.azure.com'

resource virtualNetwork 'Microsoft.Network/virtualNetworks@2024-05-01' = if (!useExistingNetwork) {
  name: virtualNetworkName
  location: location
  tags: tags
  properties: {
    addressSpace: {
      addressPrefixes: [
        '10.40.0.0/16'
      ]
    }
    subnets: [
      {
        name: 'container-apps-infrastructure'
        properties: {
          addressPrefix: '10.40.0.0/23'
          delegations: [
            {
              name: 'Microsoft.App.environments'
              properties: {
                serviceName: 'Microsoft.App/environments'
              }
            }
          ]
        }
      }
      {
        name: 'postgresql'
        properties: {
          addressPrefix: '10.40.4.0/24'
          delegations: [
            {
              name: 'Microsoft.DBforPostgreSQL.flexibleServers'
              properties: {
                serviceName: 'Microsoft.DBforPostgreSQL/flexibleServers'
              }
            }
          ]
          privateEndpointNetworkPolicies: 'Disabled'
        }
      }
    ]
  }
}

resource privateDnsZone 'Microsoft.Network/privateDnsZones@2024-06-01' = if (!useExistingNetwork) {
  name: privateDnsZoneName
  location: 'global'
  tags: tags
}

resource privateDnsLink 'Microsoft.Network/privateDnsZones/virtualNetworkLinks@2024-06-01' = if (!useExistingNetwork) {
  parent: privateDnsZone
  name: '${baseName}-postgres-link'
  location: 'global'
  properties: {
    registrationEnabled: false
    virtualNetwork: {
      id: virtualNetwork.id
    }
  }
}

output containerAppsSubnetId string = useExistingNetwork
  ? existingContainerAppsSubnetId
  : resourceId('Microsoft.Network/virtualNetworks/subnets', virtualNetworkName, 'container-apps-infrastructure')
output postgresSubnetId string = useExistingNetwork
  ? existingPostgresSubnetId
  : resourceId('Microsoft.Network/virtualNetworks/subnets', virtualNetworkName, 'postgresql')
output postgresPrivateDnsZoneId string = useExistingNetwork
  ? existingPostgresPrivateDnsZoneId
  : privateDnsZone.id
