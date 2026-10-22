package io.github.tangbailin.novaorg.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;

/** A dedicated snapshot mapper; API serialization keeps its normal visibility rules. */
public final class JsonCodec {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private JsonCodec() {}
    public static String write(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Cannot serialize durable state", e); }
    }
    public static <T> T read(String json, Class<T> type) {
        try { return MAPPER.readValue(json, type); }
        catch (Exception e) { throw new IllegalStateException("Cannot restore durable state", e); }
    }
}
