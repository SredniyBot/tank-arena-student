package edu.arena.protocol;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.function.Consumer;

/** Strict typed JSON boundary. HTTP adapters must separately cap bytes before parsing. */
public final class JsonCodec {
    private final JsonMapper mapper;

    public JsonCodec() {
        var factory =
                JsonFactory.builder()
                        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .streamReadConstraints(
                                StreamReadConstraints.builder()
                                        .maxNestingDepth(20)
                                        .maxStringLength(4096)
                                        .maxNumberLength(64)
                                        .maxDocumentLength(64L * 1024 * 1024)
                                        .build())
                        .build();
        mapper =
                JsonMapper.builder(factory)
                        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                        .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .build();
    }

    public byte[] write(Object value) throws IOException {
        return mapper.writeValueAsBytes(value);
    }

    public <T> T read(byte[] input, Class<T> type) throws IOException {
        return mapper.readValue(input, type);
    }

    /** Explicit migration boundary for versioned files. Never use for incoming HTTP requests. */
    public <T> T readMigrated(byte[] input, Class<T> type, Consumer<ObjectNode> migration)
            throws IOException {
        var tree = mapper.readTree(input);
        if (!(tree instanceof ObjectNode object)) {
            throw new IOException("Expected an object archive");
        }
        migration.accept(object);
        return mapper.treeToValue(object, type);
    }
}
