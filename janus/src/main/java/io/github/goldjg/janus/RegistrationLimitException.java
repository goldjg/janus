package io.github.goldjg.janus;

/** Registration request rejected before provisioning by an in-process abuse control. */
final class RegistrationLimitException extends RuntimeException {
    RegistrationLimitException(String message) {
        super(message);
    }
}
