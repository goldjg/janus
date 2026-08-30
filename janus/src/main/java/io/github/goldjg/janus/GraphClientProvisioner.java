package io.github.goldjg.janus;

/** Microsoft Graph implementation of the protocol-neutral provisioning boundary. */
final class GraphClientProvisioner implements ClientProvisioner {
    private final GraphClientService graph;

    GraphClientProvisioner(JanusConfig config, String correlationId) {
        this.graph = new GraphClientService(config, correlationId);
    }

    @Override
    public ProvisionedClient provision(ProvisioningRequest request) {
        GraphClientService.CreatedApplication created = graph.createApplication(request);
        return new ProvisionedClient(created.appId(), created.displayName());
    }
}
