package io.github.goldjg.janus;

/** Public Entra client identity returned by provisioning. Never contains credentials. */
public record ProvisionedClient(String clientId, String displayName) {
    public ProvisionedClient {
        if (clientId == null || clientId.isBlank() || displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("provisioned client ID and display name are required");
        }
    }
}
