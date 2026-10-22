package io.github.tangbailin.novaorg.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.tangbailin.novaorg.domain.ToolSpec;

import java.util.ArrayList;
import java.util.List;

/** Tool registry to native schemas. Strict decoding is a separate, explicit provider option.
 * Live responses retain native IDs/messages; only the offline adapter uses text calls. */
public final class ToolSchemas {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolSchemas() {
    }

    /** 生成可以直接塞进 {@code "tools":[...]} 的 JSON 字符串列表。 */
    public static List<String> of(List<ToolSpec> specs) {
        List<String> out = new ArrayList<>();
        for (ToolSpec spec : specs) {
            out.add(one(spec));
        }
        return List.copyOf(out);
    }

    public static String one(ToolSpec spec) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "function");
        ObjectNode fn = root.putObject("function");
        fn.put("name", spec.name());
        fn.put("description", spec.description() == null ? "" : spec.description());

        ObjectNode params = fn.putObject("parameters");
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");
        if (spec.parameters() != null) {
            spec.parameters().forEach((name, type) -> {
                ObjectNode p = props.putObject(name);
                p.put("type", normalizeType(type));
                if("array".equals(p.path("type").asText())) p.putObject("items").put("type","string");
                if (!"object".equals(p.get("type").asText()) && !"array".equals(p.get("type").asText())) {
                    p.put("description", type);
                }
            });
        }
        ArrayNode required = params.putArray("required");
        if (spec.requiredParameters() != null) {
            spec.requiredParameters().forEach(required::add);
        }
        params.put("additionalProperties", false);
        return root.toString();
    }

    /** 注册表里的参数类型是自由文本，这里收敛到 JSON Schema 认识的四种。 */
    private static String normalizeType(String raw) {
        if (raw == null) {
            return "string";
        }
        String t = raw.toLowerCase(java.util.Locale.ROOT);
        if (t.contains("int") || t.contains("long") || t.contains("整数")) {
            return "integer";
        }
        if (t.contains("number") || t.contains("double") || t.contains("decimal")
                || t.contains("amount") || t.contains("金额") || t.contains("数字")) {
            return "number";
        }
        if (t.contains("bool") || t.contains("布尔")) {
            return "boolean";
        }
        if (t.contains("array") || t.contains("list") || t.contains("数组")) {
            return "array";
        }
        return "string";
    }
}
