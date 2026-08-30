package io.github.goldjg.janus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Protocol-neutral registration orchestration core. */
final class RegistrationService {
    private final JanusConfig config;
    private final RegistrationPolicy policy;
    private final InMemoryRegistrationControl control;
    private final ClientProvisioner provisioner;

    RegistrationService(JanusConfig config, InMemoryRegistrationControl control, ClientProvisioner provisioner) {
        this.config = config;
        this.policy = new RegistrationPolicy(config);
        this.control = control;
        this.provisioner = provisioner;
    }

    RegistrationOutcome register(String realm, String admissionSubject, String correlationId, DcrRequest request) {
        RegistrationPolicy.ValidatedRegistration validated = policy.validate(request);
        String requestKey = digest(realm + "\u0000" + admissionSubject + "\u0000"
                + validated.clientName() + "\u0000"
                + String.join("\u0000", validated.redirectUris()) + "\u0000"
                + String.join("\u0000", validated.scopes()));
        ProvisioningRequest intent = new ProvisioningRequest(config.getTenantId(),
                config.getGatewayResourceUri(), config.getGatewayClientId(), realm, validated.clientName(),
                validated.redirectUris(), validated.scopes(), config.getGatewayScopePermissionIds(), correlationId);
        return control.execute(admissionSubject, requestKey, config, () -> provisioner.provision(intent));
    }

    static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }
}
