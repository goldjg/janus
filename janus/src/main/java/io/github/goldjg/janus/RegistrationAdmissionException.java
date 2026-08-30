package io.github.goldjg.janus;

/** Safe admission failure mapped to an OAuth error without reflecting credentials. */
final class RegistrationAdmissionException extends RuntimeException {
    RegistrationAdmissionException(String message) {
        super(message);
    }
}
