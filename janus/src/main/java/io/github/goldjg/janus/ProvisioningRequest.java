package io.github.goldjg.janus;

import java.util.List;
import java.util.Map;

/** Fully validated registration intent passed to the Entra provisioning boundary. */
public record ProvisioningRequest(
        String tenantId,
        String gatewayResourceUri,
        String gatewayClientId,
        String ownerRealm,
        String clientName,
        List<String> redirectUris,
        List<String> approvedGatewayScopes,
        Map<String, String> gatewayScopePermissionIds,
        String correlationId) {
    public ProvisioningRequest {
        tenantId = require(tenantId, "tenantId");
        gatewayResourceUri = require(gatewayResourceUri, "gatewayResourceUri");
        gatewayClientId = require(gatewayClientId, "gatewayClientId");
        ownerRealm = require(ownerRealm, "ownerRealm");
        clientName = require(clientName, "clientName");
        correlationId = require(correlationId, "correlationId");
        redirectUris = List.copyOf(redirectUris);
        approvedGatewayScopes = List.copyOf(approvedGatewayScopes);
        gatewayScopePermissionIds = Map.copyOf(gatewayScopePermissionIds);
        if (redirectUris.isEmpty() || approvedGatewayScopes.isEmpty()) {
            throw new IllegalArgumentException("redirect URIs and approved gateway scopes are required");
        }
        if (!gatewayScopePermissionIds.keySet().containsAll(approvedGatewayScopes)) {
            throw new IllegalArgumentException("every approved scope requires a delegated permission ID");
        }
    }

    private static String require(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }
}
