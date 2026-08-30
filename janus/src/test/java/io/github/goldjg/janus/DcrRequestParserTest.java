package io.github.goldjg.janus;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DcrRequestParserTest {
    private final DcrRequestParser parser = new DcrRequestParser(JanusConfig.forTesting());

    @Test
    void parse_acceptsOnlySupportedMetadata() {
        DcrRequest request = parse("""
                {"client_name":"Claude Code","redirect_uris":["http://localhost:8080/callback"],
                 "grant_types":["authorization_code"],"response_types":["code"],
                 "token_endpoint_auth_method":"none",
                 "scope":"api://11111111-1111-4111-8111-111111111111/Mcp.Access"}
                """);
        assertEquals("Claude Code", request.getClientName());
    }

    @Test
    void parse_rejectsUnknownMetadata() {
        assertThrows(DcrRequestParser.DcrParseException.class,
                () -> parse("{\"client_name\":\"x\",\"jwks_uri\":\"https://evil.example/jwks\"}"));
    }

    @Test
    void parse_rejectsDuplicateJsonProperties() {
        assertThrows(DcrRequestParser.DcrParseException.class,
                () -> parse("{\"client_name\":\"one\",\"client_name\":\"two\"}"));
    }

    @Test
    void parse_rejectsTrailingJson() {
        assertThrows(DcrRequestParser.DcrParseException.class,
                () -> parse("{} {}"));
    }

    @Test
    void parse_rejectsOversizedBodyBeforeAllocationGrowsWithoutBound() {
        String body = "{\"client_name\":\"" + "a".repeat(JanusConfig.DEFAULT_MAX_REQUEST_BODY_BYTES) + "\"}";
        assertThrows(DcrRequestParser.DcrParseException.class, () -> parse(body));
    }

    private DcrRequest parse(String body) {
        return parser.parse(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }
}
