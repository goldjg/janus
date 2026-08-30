package io.github.goldjg.janus;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure hostile-input policy for the DCR adapter. */
public final class RegistrationPolicy {
    private static final Pattern LOOPBACK_PATTERN = Pattern.compile(
            "^http://(localhost|127\\.0\\.0\\.1|\\[::1]):\\{port}(/.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CLIENT_NAME_CHARS = Pattern.compile("[A-Za-z0-9 _\\-.]+");
    private static final Pattern SCOPE_TOKEN = Pattern.compile("[\\x21\\x23-\\x5B\\x5D-\\x7E]+");
    private static final Set<String> FORBIDDEN_REDIRECT_SCHEMES = Set.of(
            "data", "file", "ftp", "javascript", "vbscript", "ws", "wss");

    private final JanusConfig config;

    public RegistrationPolicy(JanusConfig config) {
        this.config = java.util.Objects.requireNonNull(config, "config");
    }

    /** Validate and normalize the request. Defaults remain explicit in the returned value. */
    public ValidatedRegistration validate(DcrRequest request) {
        if (request == null) {
            violation("invalid_client_metadata", "registration request is required");
        }
        validateClientName(request.getClientName());
        validateRedirectUris(request.getRedirectUris());
        validateGrantTypes(request.getGrantTypes());
        validateResponseTypes(request.getResponseTypes());
        validateTokenEndpointAuthMethod(request.getTokenEndpointAuthMethod());
        List<String> scopes = validateScope(request.getScope());
        return new ValidatedRegistration(request.getClientName(), List.copyOf(request.getRedirectUris()), scopes);
    }

    void validateClientName(String clientName) {
        if (clientName == null || clientName.isBlank()) {
            violation("invalid_client_metadata", "client_name is required and must not be blank");
        }
        if (clientName.length() > config.getMaxClientNameLength()) {
            violation("invalid_client_metadata",
                    "client_name must not exceed " + config.getMaxClientNameLength() + " characters");
        }
        if (!CLIENT_NAME_CHARS.matcher(clientName).matches()) {
            violation("invalid_client_metadata",
                    "client_name contains disallowed characters; only ASCII letters, digits, spaces, "
                            + "underscores, hyphens, and dots are permitted");
        }
    }

    void validateRedirectUris(List<String> redirectUris) {
        if (redirectUris == null || redirectUris.isEmpty()) {
            violation("invalid_redirect_uri", "redirect_uris is required and must not be empty");
        }
        if (redirectUris.size() > config.getMaxRedirectUris()) {
            violation("invalid_redirect_uri",
                    "redirect_uris must not contain more than " + config.getMaxRedirectUris() + " entries");
        }
        Set<String> seen = new HashSet<>();
        for (String redirectUri : redirectUris) {
            if (redirectUri == null || redirectUri.isBlank()) {
                violation("invalid_redirect_uri", "redirect_uris contains a blank entry");
            }
            if (!seen.add(redirectUri)) {
                violation("invalid_redirect_uri", "redirect_uris contains a duplicate entry");
            }
            validateSingleRedirectUri(redirectUri);
        }
    }

    private void validateSingleRedirectUri(String raw) {
        if (raw.length() > config.getMaxFieldLength()) {
            violation("invalid_redirect_uri",
                    "redirect_uri exceeds maximum length of " + config.getMaxFieldLength());
        }
        if (raw.indexOf('*') >= 0) {
            violation("invalid_redirect_uri", "redirect_uri must not contain a wildcard");
        }
        URI uri = parseAbsoluteRedirect(raw);
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (FORBIDDEN_REDIRECT_SCHEMES.contains(scheme)) {
            violation("invalid_redirect_uri", "redirect_uri uses a forbidden scheme");
        }
        if (uri.getFragment() != null || uri.getUserInfo() != null) {
            violation("invalid_redirect_uri", "redirect_uri must not contain userinfo or a fragment");
        }
        if ((scheme.equals("http") || scheme.equals("https")) && uri.getHost() == null) {
            violation("invalid_redirect_uri", "network redirect_uri must include a host");
        }
        if (scheme.equals("http") && (!isLoopbackHost(uri.getHost()) || uri.getPort() < 1)) {
            violation("invalid_redirect_uri",
                    "http redirect_uri requires an explicit port on localhost, 127.0.0.1, or [::1]");
        }
        boolean matched = config.getAllowedRedirectPatterns().stream()
                .anyMatch(pattern -> matchesConfiguredPattern(pattern, raw, uri));
        if (!matched) {
            violation("invalid_redirect_uri", "redirect_uri does not exactly match a permitted rule");
        }
    }

    void validateGrantTypes(List<String> values) {
        exactSingleton(values, "authorization_code", "grant_types");
    }

    void validateResponseTypes(List<String> values) {
        exactSingleton(values, "code", "response_types");
    }

    void validateTokenEndpointAuthMethod(String method) {
        if (method != null && !method.equals("none")) {
            violation("invalid_client_metadata",
                    "token_endpoint_auth_method must be none or omitted; JANUS creates public clients only");
        }
    }

    List<String> validateScope(String scope) {
        if (scope == null || scope.isBlank()) {
            violation("invalid_client_metadata", "scope is required and must identify an approved gateway scope");
        }
        if (scope.length() > config.getMaxFieldLength()) {
            violation("invalid_client_metadata", "scope exceeds maximum length of " + config.getMaxFieldLength());
        }
        if (!scope.equals(scope.trim()) || scope.contains("  ")) {
            violation("invalid_client_metadata", "scope must use single spaces with no surrounding whitespace");
        }
        List<String> accepted = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String token : scope.split(" ", -1)) {
            if (!SCOPE_TOKEN.matcher(token).matches()) {
                violation("invalid_client_metadata", "scope contains a malformed token");
            }
            if (!seen.add(token)) {
                violation("invalid_client_metadata", "scope contains a duplicate value");
            }
            if (!config.getAllowedGatewayScopes().contains(token)) {
                violation("invalid_client_metadata", "scope contains a value that is not approved for the gateway");
            }
            accepted.add(token);
        }
        return List.copyOf(accepted);
    }

    static void validateConfiguredRedirectPattern(String pattern) {
        Matcher loopback = LOOPBACK_PATTERN.matcher(pattern);
        if (loopback.matches()) {
            String path = loopback.group(2);
            if (path.indexOf('*') >= 0 && !path.endsWith("/*")) {
                throw new IllegalArgumentException("redirect wildcard is permitted only as a final /*");
            }
            if (path.substring(0, path.length() - (path.endsWith("/*") ? 1 : 0)).contains("*")) {
                throw new IllegalArgumentException("redirect pattern contains an unsupported wildcard");
            }
            return;
        }
        if (pattern.contains("{port}") || pattern.contains("*")) {
            throw new IllegalArgumentException(
                    "redirect pattern variables are allowed only for explicit HTTP loopback rules");
        }
        URI exact = parseAbsoluteConfigUri(pattern);
        String scheme = exact.getScheme().toLowerCase(Locale.ROOT);
        if (FORBIDDEN_REDIRECT_SCHEMES.contains(scheme)) {
            throw new IllegalArgumentException("redirect pattern uses a forbidden scheme");
        }
        if (exact.getFragment() != null || exact.getUserInfo() != null) {
            throw new IllegalArgumentException("redirect pattern must not contain userinfo or a fragment");
        }
        if (scheme.equals("http")) {
            throw new IllegalArgumentException("HTTP redirect patterns must use the explicit {port} loopback form");
        }
        if (scheme.equals("https") && exact.getHost() == null) {
            throw new IllegalArgumentException("HTTPS redirect pattern must include a host");
        }
    }

    private static boolean matchesConfiguredPattern(String pattern, String raw, URI uri) {
        Matcher matcher = LOOPBACK_PATTERN.matcher(pattern);
        if (!matcher.matches()) {
            return pattern.equals(raw);
        }
        if (!uri.getScheme().equalsIgnoreCase("http") || uri.getPort() < 1
                || !normaliseLoopback(matcher.group(1)).equals(normaliseLoopback(uri.getHost()))) {
            return false;
        }
        String configuredPath = matcher.group(2);
        String actualPath = uri.getRawPath();
        if (actualPath == null || actualPath.isEmpty()) {
            actualPath = "/";
        }
        boolean pathMatches = configuredPath.endsWith("/*")
                ? actualPath.startsWith(configuredPath.substring(0, configuredPath.length() - 1))
                : actualPath.equals(configuredPath);
        return pathMatches && uri.getRawQuery() == null;
    }

    private static URI parseAbsoluteRedirect(String raw) {
        try {
            URI uri = new URI(raw);
            if (!uri.isAbsolute() || uri.getScheme() == null) {
                violation("invalid_redirect_uri", "redirect_uri must be an absolute URI");
            }
            return uri;
        } catch (URISyntaxException e) {
            violation("invalid_redirect_uri", "redirect_uri is malformed");
            throw new AssertionError("unreachable");
        }
    }

    private static URI parseAbsoluteConfigUri(String raw) {
        try {
            URI uri = new URI(raw);
            if (!uri.isAbsolute() || uri.getScheme() == null) {
                throw new IllegalArgumentException("redirect pattern must be an absolute URI");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("redirect pattern is malformed", e);
        }
    }

    private static boolean isLoopbackHost(String host) {
        return host != null && (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1")
                || host.equals("::1") || host.equals("[::1]"));
    }

    private static String normaliseLoopback(String host) {
        return host == null ? "" : host.replace("[", "").replace("]", "").toLowerCase(Locale.ROOT);
    }

    private static void exactSingleton(List<String> values, String allowed, String field) {
        if (values != null && (values.size() != 1 || !allowed.equals(values.get(0)))) {
            violation("invalid_client_metadata", field + " must be [\"" + allowed + "\"] or omitted");
        }
    }

    private static void violation(String code, String description) {
        throw new RegistrationPolicyViolationException(code, description);
    }

    /** Protocol-neutral result consumed by the provisioning core. */
    public record ValidatedRegistration(String clientName, List<String> redirectUris, List<String> scopes) {
        public ValidatedRegistration {
            redirectUris = List.copyOf(redirectUris);
            scopes = List.copyOf(scopes);
        }
    }
}
