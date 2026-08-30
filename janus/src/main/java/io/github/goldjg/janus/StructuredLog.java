package io.github.goldjg.janus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;

/** Small JSON logger that keeps JANUS events machine-queryable and escaped. */
final class StructuredLog {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StructuredLog() { }

    static void info(Logger logger, Object... fields) {
        logger.info("{}", json(fields));
    }

    static void warn(Logger logger, Object... fields) {
        logger.warn("{}", json(fields));
    }

    static void error(Logger logger, Object... fields) {
        logger.error("{}", json(fields));
    }

    private static String json(Object... fields) {
        if (fields == null || fields.length == 0 || fields.length % 2 != 0) {
            throw new IllegalArgumentException("structured log fields must be name/value pairs");
        }
        ObjectNode event = MAPPER.createObjectNode();
        for (int index = 0; index < fields.length; index += 2) {
            String name = String.valueOf(fields[index]);
            Object value = fields[index + 1];
            if (value == null) {
                event.putNull(name);
            } else if (value instanceof Boolean booleanValue) {
                event.put(name, booleanValue);
            } else if (value instanceof Integer integerValue) {
                event.put(name, integerValue);
            } else if (value instanceof Long longValue) {
                event.put(name, longValue);
            } else {
                event.put(name, String.valueOf(value));
            }
        }
        return event.toString();
    }
}
