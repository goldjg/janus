package io.github.goldjg.janus;

/**
 * Thrown when the {@link GraphClientService} encounters an unexpected error
 * while calling Microsoft Graph.
 *
 * <p>This exception wraps infrastructure errors (HTTP errors, timeouts,
 * serialization failures). Callers should map this to an RFC 7591
 * {@code server_error} response without leaking the internal detail.
 */
public class JanusRegistrationException extends RuntimeException {

    public JanusRegistrationException(String message) {
        super(message);
    }

    public JanusRegistrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
