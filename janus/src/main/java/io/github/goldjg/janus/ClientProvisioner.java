package io.github.goldjg.janus;

/**
 * Protocol-neutral application provisioning boundary.
 *
 * <p>DCR and a future CIMD adapter both target this interface. Implementations
 * create client identities only; they must never issue or handle gateway bearer tokens.
 */
@FunctionalInterface
public interface ClientProvisioner {
    ProvisionedClient provision(ProvisioningRequest request);
}
