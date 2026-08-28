package io.github.goldjg.janus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GraphClientService}.
 *
 * <p>These tests cover the pure logic in {@link GraphClientService} that does
 * not require live Azure credentials or a live Graph API connection.
 *
 * <p>Integration tests requiring a live Entra tenant are annotated
 * {@code @Tag("live-integration")} and are opt-in only.
 */
class GraphClientServiceTest {

    // ═══════════════════════════════════════════════════════════════════════
    // buildDisplayName — contract assertions
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void buildDisplayName_startsWithJanusPrefix() {
        String name = GraphClientService.buildDisplayName("myrealm", "My Client");
        assertTrue(name.startsWith("janus-"), "Display name must start with 'janus-'");
    }

    @Test
    void buildDisplayName_containsRealmName() {
        String name = GraphClientService.buildDisplayName("myrealm", "My Client");
        assertTrue(name.contains("myrealm"), "Display name must contain the realm name");
    }

    @Test
    void buildDisplayName_containsSanitisedClientName() {
        String name = GraphClientService.buildDisplayName("myrealm", "My Client");
        assertTrue(name.contains("my-client"), "Display name must contain the sanitised client name");
    }

    @Test
    void buildDisplayName_sanitisesSpecialCharacters() {
        String name = GraphClientService.buildDisplayName("realm", "My Client! <test>");
        // Should not contain raw special characters
        assertFalse(name.contains("!"), "Display name must not contain '!'");
        assertFalse(name.contains("<"), "Display name must not contain '<'");
        assertFalse(name.contains(">"), "Display name must not contain '>'");
    }

    @Test
    void buildDisplayName_isUniqueBetweenCalls() {
        String name1 = GraphClientService.buildDisplayName("realm", "My Client");
        String name2 = GraphClientService.buildDisplayName("realm", "My Client");
        assertNotEquals(name1, name2,
                "Two calls with the same inputs should produce different display names (UUID suffix)");
    }

    @Test
    void buildDisplayName_handlesNullClientName() {
        // Null client name should not throw; sanitised to 'unknown'
        assertDoesNotThrow(() -> GraphClientService.buildDisplayName("realm", null));
    }

    @Test
    void buildDisplayName_handlesNullRealm() {
        assertDoesNotThrow(() -> GraphClientService.buildDisplayName(null, "My Client"));
    }

    @ParameterizedTest
    @CsvSource({
            "myrealm,  Claude Code,  janus-myrealm-claude-code",
            "janus,    MyCursor,     janus-janus-mycursor",
            "prod,     My App 1.0,   janus-prod-my-app-1-0",
    })
    void buildDisplayName_producesExpectedPrefix(String realm, String client, String expectedPrefix) {
        String name = GraphClientService.buildDisplayName(realm, client);
        assertTrue(name.startsWith(expectedPrefix),
                "Expected display name to start with '" + expectedPrefix + "' but got: " + name);
    }

    @Test
    void buildDisplayName_doesNotExceedReasonableLength() {
        String longRealm = "a".repeat(100);
        String longClient = "b".repeat(100);
        String name = GraphClientService.buildDisplayName(longRealm, longClient);
        // 6 ("janus-") + 20 (realm) + 1 ("-") + 30 (client) + 1 ("-") + 8 (uuid) = 66
        assertTrue(name.length() <= 100,
                "Display name should be bounded; got length " + name.length());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Constants — contract assertions
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void janusTagConstant_isCorrect() {
        assertEquals("janus-managed", GraphClientService.TAG_JANUS_MANAGED);
    }

    @Test
    void realmTagPrefix_isCorrect() {
        assertEquals("janus-realm:", GraphClientService.TAG_REALM_PREFIX);
    }

    @Test
    void realmTag_isCorrectForGivenRealm() {
        String tag = GraphClientService.TAG_REALM_PREFIX + "myrealm";
        assertEquals("janus-realm:myrealm", tag);
    }
}
