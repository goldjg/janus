package io.github.goldjg.janus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RegistrationPolicy}.
 *
 * <p>Every test maps directly to a contract assertion: a specific validation
 * rule that the policy enforces. These tests prove the approved registration
 * policy behaviour, not just the implementation that happened to be written.
 */
class RegistrationPolicyTest {

    private RegistrationPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new RegistrationPolicy(JanusConfig.fromEnvironment());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // client_name validation
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void validate_acceptsValidClientName() {
        var ex = assertDoesNotThrow(() -> policy.validateClientName("Claude Code"));
    }

    @Test
    void validate_rejectsNullClientName() {
        var ex = assertThrows(RegistrationPolicyViolationException.class,
                () -> policy.validateClientName(null));
        assertEquals("invalid_client_metadata", ex.getErrorCode());
    }

    @Test
    void validate_rejectsBlankClientName() {
        var ex = assertThrows(RegistrationPolicyViolationException.class,
                () -> policy.validateClientName("   "));
        assertEquals("invalid_client_metadata", ex.getErrorCode());
    }

    @Test
    void validate_rejectsClientNameExceedingMaxLength() {
        String longName = "A".repeat(JanusConfig.DEFAULT_MAX_CLIENT_NAME_LENGTH + 1);
        var ex = assertThrows(RegistrationPolicyViolationException.class,
                () -> policy.validateClientName(longName));
        assertEquals("invalid_client_metadata", ex.getErrorCode());
    }

    @Test
    void validate_acceptsClientNameAtExactMaxLength() {
        String maxName = "A".repeat(JanusConfig.DEFAULT_MAX_CLIENT_NAME_LENGTH);
        assertDoesNotThrow(() -> policy.validateClientName(maxName));
    }

    @ParameterizedTest
    @ValueSource(strings = {"<script>", "name; DROP TABLE", "name\u0000null", "name\r\n"})
    void validate_rejectsClientNameWithDisallowedCharacters(String name) {
        var ex = assertThrows(RegistrationPolicyViolationException.class,
                () -> policy.validateClientName(name));
        assertEquals("invalid_client_metadata", ex.getErrorCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"MyApp", "My App", "my-app", "my.app", "my_app", "App 1.2"})
    void validate_acceptsClientNamesWithAllowedCharacters(String name) {
        assertDoesNotThrow(() -> policy.validateClientName(name));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // redirect_uris validation
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void validate_acceptsLocalhostRedirectUri() {
        assertDoesNotThrow(() -> policy.validateRedirectUris(
                List.of("http://localhost:8080/callback")));
    }

    @Test
    void validate_acceptsIpv4LoopbackRedirectUri() {
        assertDoesNotThrow(() -> policy.validateRedirectUris(
                List.of("http://127.0.0.1:54321/callback")));
    }

    @Test
    void validate_acceptsIpv6LoopbackRedirectUri() {
        assertDoesNotThrow(() -> policy.validateRedirectUris(
                List.of("http://[::1]:9000/oauth/callback")));
    }

    @Test
    void validate_rejectsNullRedirectUris() {
        var ex = assertThrows(RegistrationPolicyViolationException.class,
                () -> policy.validateRedirectUris(null));
        assertEquals("invalid_redirect_uri", ex.getErrorCode());
    }

    @Test
    void validate_rejectsEmptyRedirectUriList() {
        var ex = assertThrows(RegistrationPolicyViolationException.class,
                () -> policy.validateRedirectUris(List.of()));
        assertEquals("invalid_redirect_uri", ex.getErrorCode());
    }

    @Test
    void validate_rejectsHttpRedirectUriWithNonLoopbackHost() {
        var ex = assertThrows(RegistrationPolicyViolationException.class,
                () -> policy.validateRedirectUris(
                        List.of("http://example.com/callback")));
        assertEquals("invalid_redirect_uri", ex.getErrorCode());
    }

    @Test
    void validate_rejectsMalformedUri() {
        var ex = assertThrows(RegistrationPolicyViolationException.class,
                () -> policy.validateRedirectUris(List.of("not a uri")));
        assertEquals("invalid_redirect_uri", ex.getErrorCode());
    }

    @Test
    void validate_rejectsUriWithFragment() {
        var ex = assertThrows(RegistrationPolicyViolationException.class,
                () -> policy.validateRedirectUris(
                        List.of("http://localhost:8080/callback#fragment")));
        assertEquals("invalid_redirect_uri", ex.getErrorCode());
    }

    @Test
    void validate_rejectsDuplicateRedirectUris() {
        var ex = assertThrows(RegistrationPolicyViolationException.class,
                () -> policy.validateRedirectUris(
                        List.of("http://localhost:8080/cb", "http://localhost:8080/cb")));
        assertEquals("invalid_redirect_uri", ex.getErrorCode());
    }

    @Test
    void validate_rejectsTooManyRedirectUris() {
        var uris = new java.util.ArrayList<String>();
        for (int i = 0; i <= JanusConfig.DEFAULT_MAX_REDIRECT_URIS; i++) {
            uris.add("http://localhost:" + (8000 + i) + "/cb");
        }
        var ex = assertThrows(RegistrationPolicyViolationException.class,
                () -> policy.validateRedirectUris(uris));
        assertEquals("invalid_redirect_uri", ex.getErrorCode());
    }

    @Test
    void validate_rejectsDataSchemeUri() {
        var ex = assertThrows(RegistrationPolicyViolationException.class,
                () -> policy.validateRedirectUris(
                        List.of("data:text/html,<script>alert(1)</script>")));
        assertEquals("invalid_redirect_uri", ex.getErrorCode());
    }

    @Test
    void validate_rejectsJavascriptSchemeUri() {
        var ex = assertThrows(RegistrationPolicyViolationException.class,
                () -> policy.validateRedirectUris(
                        List.of("javascript:alert(1)")));
        assertEquals("invalid_redirect_uri", ex.getErrorCode());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // grant_types validation
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void validate_acceptsNullGrantTypes() {
        assertDoesNotThrow(() -> policy.validateGrantTypes(null));
    }

    @Test
    void validate_acceptsAuthorizationCodeGrantType() {
        assertDoesNotThrow(() -> policy.validateGrantTypes(List.of("authorization_code")));
    }

    @Test
    void validate_rejectsClientCredentialsGrantType() {
        var ex = assertThrows(RegistrationPolicyViolationException.class,
                () -> policy.validateGrantTypes(List.of("client_credentials")));
        assertEquals("invalid_client_metadata", ex.getErrorCode());
    }

    @Test
    void validate_rejectsImplicitGrantType() {
        var ex = assertThrows(RegistrationPolicyViolationException.class,
                () -> policy.validateGrantTypes(List.of("implicit")));
        assertEquals("invalid_client_metadata", ex.getErrorCode());
    }

    @Test
    void validate_rejectsMultipleGrantTypes() {
        var ex = assertThrows(RegistrationPolicyViolationException.class,
                () -> policy.validateGrantTypes(List.of("authorization_code", "client_credentials")));
        assertEquals("invalid_client_metadata", ex.getErrorCode());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // response_types validation
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void validate_acceptsNullResponseTypes() {
        assertDoesNotThrow(() -> policy.validateResponseTypes(null));
    }

    @Test
    void validate_acceptsCodeResponseType() {
        assertDoesNotThrow(() -> policy.validateResponseTypes(List.of("code")));
    }

    @Test
    void validate_rejectsTokenResponseType() {
        var ex = assertThrows(RegistrationPolicyViolationException.class,
                () -> policy.validateResponseTypes(List.of("token")));
        assertEquals("invalid_client_metadata", ex.getErrorCode());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // token_endpoint_auth_method validation
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void validate_acceptsNullTokenEndpointAuthMethod() {
        assertDoesNotThrow(() -> policy.validateTokenEndpointAuthMethod(null));
    }

    @Test
    void validate_acceptsNoneTokenEndpointAuthMethod() {
        assertDoesNotThrow(() -> policy.validateTokenEndpointAuthMethod("none"));
    }

    @Test
    void validate_rejectsClientSecretBasicAuthMethod() {
        var ex = assertThrows(RegistrationPolicyViolationException.class,
                () -> policy.validateTokenEndpointAuthMethod("client_secret_basic"));
        assertEquals("invalid_client_metadata", ex.getErrorCode());
    }

    @Test
    void validate_rejectsPrivateKeyJwtAuthMethod() {
        var ex = assertThrows(RegistrationPolicyViolationException.class,
                () -> policy.validateTokenEndpointAuthMethod("private_key_jwt"));
        assertEquals("invalid_client_metadata", ex.getErrorCode());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Full request validation
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void validate_acceptsMinimalValidRequest() {
        DcrRequest request = new DcrRequest();
        request.setClientName("Claude Code");
        request.setRedirectUris(List.of("http://localhost:8080/callback"));
        assertDoesNotThrow(() -> policy.validate(request));
    }

    @Test
    void validate_acceptsFullValidRequest() {
        DcrRequest request = new DcrRequest();
        request.setClientName("My MCP Client");
        request.setRedirectUris(List.of("http://127.0.0.1:9000/oauth/callback"));
        request.setGrantTypes(List.of("authorization_code"));
        request.setResponseTypes(List.of("code"));
        request.setTokenEndpointAuthMethod("none");
        assertDoesNotThrow(() -> policy.validate(request));
    }
}
