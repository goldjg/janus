package io.github.goldjg.janus;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Strict, size-bounded parser for the hostile DCR HTTP body. */
final class DcrRequestParser {
    private final ObjectMapper mapper;
    private final int maxBodyBytes;

    DcrRequestParser(JanusConfig config) {
        JsonFactory factory = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(8)
                        .maxStringLength(config.getMaxFieldLength())
                        .maxNumberLength(32)
                        .build())
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        this.mapper = new ObjectMapper(factory)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
                .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);
        this.maxBodyBytes = config.getMaxRequestBodyBytes();
    }

    DcrRequest parse(InputStream input) {
        if (input == null) {
            throw new DcrParseException("Request body is required");
        }
        try {
            DcrRequest request = mapper.readValue(new BoundedInputStream(input, maxBodyBytes), DcrRequest.class);
            if (request == null) {
                throw new DcrParseException("Request body must contain a JSON object");
            }
            return request;
        } catch (DcrParseException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new DcrParseException("Request body is invalid, oversized, or contains unsupported metadata");
        }
    }

    static final class DcrParseException extends RuntimeException {
        DcrParseException(String message) {
            super(message);
        }
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private final long maximum;
        private long read;

        private BoundedInputStream(InputStream input, long maximum) {
            super(input);
            this.maximum = maximum;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0) {
                count(count);
            }
            return count;
        }

        private void count(int amount) throws IOException {
            read += amount;
            if (read > maximum) {
                throw new IOException("DCR request exceeds configured byte limit");
            }
        }
    }
}
