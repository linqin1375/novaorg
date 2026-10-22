package io.github.tangbailin.novaorg.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/** A provider tool request. Preserve the ID and raw JSON for the next native conversation turn. */
public record LlmToolCall(String id, String name, String arguments) {
    private static final ObjectMapper JSON = new ObjectMapper();
    public LlmToolCall {
        if (id == null || id.isBlank() || name == null || name.isBlank())
            throw new IllegalArgumentException("Tool call is missing its id or function name");
        try {
            if (!JSON.readTree(arguments).isObject()) throw new IllegalArgumentException("Tool arguments must be an object");
        } catch (java.io.IOException | NullPointerException e) {
            throw new IllegalArgumentException("Invalid tool arguments JSON", e);
        }
    }
    public Map<String,Object> argumentMap() {
        try { return JSON.readValue(arguments, new TypeReference<Map<String,Object>>() {}); }
        catch (java.io.IOException e) { throw new IllegalArgumentException("Invalid tool arguments JSON", e); }
    }
}
