package io.github.tangbailin.novaorg.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.tangbailin.novaorg.domain.ToolCall;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads tool requests out of a model response.
 *
 * <p>The contract is deliberately close to how hosted models emit tool calls, so swapping in
 * native function-calling later only changes this class:
 * <pre>
 * TOOL_CALL {"name": "refund.execute", "arguments": {"orderId": "A100", "amount": 129.0}}
 * </pre>
 * Malformed lines are skipped rather than failing the step, because a model that emits one bad
 * call should still be able to finish its work (and the miss shows up in the metrics).
 */
public final class ToolRequestParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern TOOL_CALL = Pattern.compile("^\\s*TOOL_CALL\\s*(\\{.*})\\s*$");

    private ToolRequestParser() {
    }

    public static List<ToolCall> parse(String modelText, String roleId) {
        List<ToolCall> calls = new ArrayList<>();
        if (modelText == null || modelText.isBlank()) {
            return calls;
        }
        for (String line : modelText.split("\\R")) {
            Matcher m = TOOL_CALL.matcher(line);
            if (!m.find()) {
                continue;
            }
            try {
                JsonNode node = MAPPER.readTree(m.group(1));
                String name = node.path("name").asText("");
                if (name.isBlank()) {
                    continue;
                }
                Map<String, Object> args = new LinkedHashMap<>();
                JsonNode argsNode = node.path("arguments");
                if (argsNode.isObject()) {
                    argsNode.fields().forEachRemaining(e -> args.put(e.getKey(), toJava(e.getValue())));
                }
                calls.add(new ToolCall(name, args, roleId));
            } catch (Exception ignored) {
                // a single unparsable call must not abort the step
            }
        }
        return calls;
    }

    private static Object toJava(JsonNode node) {
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isObject() || node.isArray()) {
            return MAPPER.convertValue(node,Object.class);
        }
        return node.asText();
    }
}
