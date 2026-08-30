@description('Azure region for PostgreSQL.')
param location string

@description('Globally unique PostgreSQL Flexible Server name.')
param serverName string

@description('Database name used by Keycloak.')
param databaseName string = 'keycloak'

@description('PostgreSQL administrator login name.')
param administratorLogin string = 'janusadmin'

@secure()
@description('PostgreSQL administrator password. Stored only as an Azure deployment secure parameter and Container Apps secret.')
param administratorPassword string

@description('Delegated PostgreSQL subnet resource ID.')
param delegatedSubnetId string

@description('PostgreSQL private DNS zone resource ID.')
param privateDnsZoneId string

@description('PostgreSQL SKU name.')
param skuName string = 'Standard_B1ms'

@description('PostgreSQL SKU tier.')
@allowed([
  'Burstable'
  'GeneralPurpose'
  'MemoryOptimized'
])
param skuTier string = 'Burstable'

@description('Enable same-zone standby high availability. Use a supported General Purpose or Memory Optimized SKU.')
param highAvailability bool = false

@description('Backup retention in days.')
@minValue(7)
@maxValue(35)
param backupRetentionDays int = 7

@description('Resource tags.')
param tags object = {}

resource server 'Microsoft.DBforPostgreSQL/flexibleServers@2024-08-01' = {
  name: serverName
  location: location
  tags: tags
  sku: {
    name: skuName
    tier: skuTier
  }
  properties: {
    administratorLogin: administratorLogin
    administratorLoginPassword: administratorPassword
    version: '16'
    authConfig: {
      activeDirectoryAuth: 'Disabled'
      passwordAuth: 'Enabled'
    }
    backup: {
      backupRetentionDays: backupRetentionDays
      geoRedundantBackup: 'Disabled'
    }
    highAvailability: {
      mode: highAvailability ? 'SameZone' : 'Disabled'
    }
    network: {
      delegatedSubnetResourceId: delegatedSubnetId
      privateDnsZoneArmResourceId: privateDnsZoneId
      publicNetworkAccess: 'Disabled'
    }
    storage: {
      autoGrow: 'Enabled'
      storageSizeGB: 32
    }
  }
}

resource database 'Microsoft.DBforPostgreSQL/flexibleServers/databases@2024-08-01' = {
  parent: server
  name: databaseName
  properties: {
    charset: 'UTF8'
    collation: 'en_US.utf8'
  }
}

output serverId string = server.id
output serverName string = server.name
output serverFqdn string = server.properties.fullyQualifiedDomainName
output databaseName string = database.name
