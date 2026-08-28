package io.github.goldjg.janus;

/**
 * Thrown when the {@link RegistrationPolicy} rejects a DCR request.
 *
 * <p>Carries an RFC 7591 error code and a human-readable description.
 */
public class RegistrationPolicyViolationException extends RuntimeException {

    /** RFC 7591 error code. */
    private final String errorCode;

    /** Human-readable description safe to return to the caller. */
    private final String errorDescription;

    public RegistrationPolicyViolationException(String errorCode, String errorDescription) {
        super(errorCode + ": " + errorDescription);
        this.errorCode = errorCode;
        this.errorDescription = errorDescription;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorDescription() {
        return errorDescription;
    }
}
