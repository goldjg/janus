package io.github.goldjg.janus;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates a {@link DcrRequest} against JANUS registration policy.
 *
 * <p>This class is a pure function: it takes a request and either returns
 * normally (validation passed) or throws a
 * {@link RegistrationPolicyViolationException} (validation failed). It has
 * no side effects.
 *
 * <h2>Policy summary</h2>
 * <ul>
 *   <li>{@code client_name}: required, 1–{@code maxClientNameLength} characters,
 *       safe character set {@code [A-Za-z0-9 _\-\.]}</li>
 *   <li>{@code redirect_uris}: required, non-empty, each URI must match an
 *       allowlist pattern, no duplicates, maximum
 *       {@code maxRedirectUris} entries</li>
 *   <li>{@code grant_types}: if present, must be exactly
 *       {@code ["authorization_code"]}</li>
 *   <li>{@code response_types}: if present, must be exactly
 *       {@code ["code"]}</li>
 *   <li>{@code token_endpoint_auth_method}: if present, must be
 *       {@code "none"}</li>
 *   <li>{@code scope}: if present, must be a subset of the configured
 *       allowed scopes (currently unrestricted beyond length)</li>
 * </ul>
 */
public class RegistrationPolicy {

    private final JanusConfig config;

    /** Compiled pattern based on config's max client name length — built once in the constructor. */
    private final Pattern clientNamePattern;

    public RegistrationPolicy(JanusConfig config) {
        this.config = config;
        // Bounded quantifier on a simple character class: no catastrophic backtracking risk.
        this.clientNamePattern = Pattern.compile(
                "^[A-Za-z0-9 _\\-.]{1," + config.getMaxClientNameLength() + "}$");
    }

    /**
     * Validate {@code request} against policy.
     *
     * @param request the parsed DCR request; must not be null.
     * @throws RegistrationPolicyViolationException if validation fails.
     */
    public void validate(DcrRequest request) {
        validateClientName(request.getClientName());
        validateRedirectUris(request.getRedirectUris());
        validateGrantTypes(request.getGrantTypes());
        validateResponseTypes(request.getResponseTypes());
        validateTokenEndpointAuthMethod(request.getTokenEndpointAuthMethod());
        validateScope(request.getScope());
    }

    // ─── Validators ───────────────────────────────────────────────────────

    void validateClientName(String clientName) {
        if (clientName == null || clientName.isBlank()) {
            throw new RegistrationPolicyViolationException(
                    "invalid_client_metadata",
                    "client_name is required and must not be blank");
        }
        if (clientName.length() > config.getMaxClientNameLength()) {
            throw new RegistrationPolicyViolationException(
                    "invalid_client_metadata",
                    "client_name must not exceed " + config.getMaxClientNameLength() + " characters");
        }
        // Validate character set using the pre-compiled pattern.
        if (!clientNamePattern.matcher(clientName).matches()) {
            throw new RegistrationPolicyViolationException(
                    "invalid_client_metadata",
                    "client_name contains disallowed characters; "
                            + "only letters, digits, spaces, underscores, hyphens, and dots are permitted");
        }
    }

    void validateRedirectUris(List<String> redirectUris) {
        if (redirectUris == null || redirectUris.isEmpty()) {
            throw new RegistrationPolicyViolationException(
                    "invalid_redirect_uri",
                    "redirect_uris is required and must not be empty");
        }
        if (redirectUris.size() > config.getMaxRedirectUris()) {
            throw new RegistrationPolicyViolationException(
                    "invalid_redirect_uri",
                    "redirect_uris must not contain more than " + config.getMaxRedirectUris()
                            + " entries");
        }
        // Reject duplicates
        Set<String> seen = new HashSet<>();
        for (String uri : redirectUris) {
            if (!seen.add(uri)) {
                throw new RegistrationPolicyViolationException(
                        "invalid_redirect_uri",
                        "redirect_uris contains duplicate entry: " + sanitiseForError(uri));
            }
        }
        // Validate each URI
        for (String uri : redirectUris) {
            validateSingleRedirectUri(uri);
        }
    }

    private void validateSingleRedirectUri(String uriStr) {
        if (uriStr == null || uriStr.isBlank()) {
            throw new RegistrationPolicyViolationException(
                    "invalid_redirect_uri", "redirect_uris contains a blank entry");
        }
        if (uriStr.length() > config.getMaxFieldLength()) {
            throw new RegistrationPolicyViolationException(
                    "invalid_redirect_uri",
                    "redirect_uri exceeds maximum length of " + config.getMaxFieldLength());
        }

        // Parse the URI to detect obvious malformation and fragments
        URI parsed;
        try {
            parsed = new URI(uriStr);
        } catch (URISyntaxException e) {
            throw new RegistrationPolicyViolationException(
                    "invalid_redirect_uri",
                    "redirect_uri is not a valid URI: " + sanitiseForError(uriStr));
        }

        // Fragments are explicitly forbidden by RFC 6749 §3.1.2
        if (parsed.getFragment() != null) {
            throw new RegistrationPolicyViolationException(
                    "invalid_redirect_uri",
                    "redirect_uri must not contain a fragment");
        }

        // Scheme must be present
        String scheme = parsed.getScheme();
        if (scheme == null) {
            throw new RegistrationPolicyViolationException(
                    "invalid_redirect_uri",
                    "redirect_uri must include a scheme");
        }

        // Reject data:, javascript:, and other dangerous schemes
        if (scheme.equalsIgnoreCase("data") || scheme.equalsIgnoreCase("javascript")) {
            throw new RegistrationPolicyViolationException(
                    "invalid_redirect_uri",
                    "redirect_uri scheme '" + scheme + "' is not permitted");
        }

        // Check against the configured allowlist
        boolean allowed = false;
        for (String pattern : config.getAllowedRedirectPatterns()) {
            if (uriStr.startsWith(pattern.trim())) {
                allowed = true;
                break;
            }
        }
        if (!allowed) {
            throw new RegistrationPolicyViolationException(
                    "invalid_redirect_uri",
                    "redirect_uri does not match any permitted pattern");
        }

        // For http:// URIs that are allowed, the host must be loopback only
        if (scheme.equalsIgnoreCase("http")) {
            String host = parsed.getHost();
            if (host == null || !isLoopbackHost(host)) {
                throw new RegistrationPolicyViolationException(
                        "invalid_redirect_uri",
                        "http redirect_uri is only permitted for loopback addresses "
                                + "(localhost, 127.0.0.1, [::1])");
            }
        }
    }

    void validateGrantTypes(List<String> grantTypes) {
        if (grantTypes == null) {
            return; // defaults to authorization_code
        }
        if (grantTypes.size() != 1 || !grantTypes.get(0).equals("authorization_code")) {
            throw new RegistrationPolicyViolationException(
                    "invalid_client_metadata",
                    "grant_types must be [\"authorization_code\"] or omitted");
        }
    }

    void validateResponseTypes(List<String> responseTypes) {
        if (responseTypes == null) {
            return; // defaults to code
        }
        if (responseTypes.size() != 1 || !responseTypes.get(0).equals("code")) {
            throw new RegistrationPolicyViolationException(
                    "invalid_client_metadata",
                    "response_types must be [\"code\"] or omitted");
        }
    }

    void validateTokenEndpointAuthMethod(String method) {
        if (method == null) {
            return; // defaults to none
        }
        if (!method.equals("none")) {
            throw new RegistrationPolicyViolationException(
                    "invalid_client_metadata",
                    "token_endpoint_auth_method must be \"none\" or omitted; "
                            + "JANUS creates public clients only");
        }
    }

    void validateScope(String scope) {
        if (scope == null) {
            return;
        }
        if (scope.length() > config.getMaxFieldLength()) {
            throw new RegistrationPolicyViolationException(
                    "invalid_client_metadata",
                    "scope exceeds maximum length of " + config.getMaxFieldLength());
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private static boolean isLoopbackHost(String host) {
        return host.equals("localhost")
                || host.equals("127.0.0.1")
                || host.equals("[::1]")
                || host.equals("::1");
    }

    /**
     * Returns a truncated, safe representation of a string for inclusion in an
     * error message. Never includes the full original value to avoid reflecting
     * injected content.
     */
    private static String sanitiseForError(String value) {
        if (value == null) {
            return "(null)";
        }
        String safe = value.replaceAll("[\\r\\n\\t]", " ");
        return safe.length() > 80 ? safe.substring(0, 80) + "…" : safe;
    }
}
