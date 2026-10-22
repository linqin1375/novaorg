package io.github.tangbailin.novaorg.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.tangbailin.novaorg.domain.ToolSpec;
import io.github.tangbailin.novaorg.org.ToolCatalog;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 原生 function calling 的两端：发出去的 schema，和收回来的 tool_calls。
 *
 * <p>这两端都容易静默出错——schema 类型写错，模型就会把金额当字符串返回；
 * arguments 双重转义，下游解析器就拿到一堆转义符。两者都不会抛异常，只会让效果变差，
 * 所以必须有测试盯着。
 */
class ToolCallingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void schemasAreValidJsonSchemaWithConvergedTypes() throws Exception {
        ToolCatalog catalog = new ToolCatalog();
        List<ToolSpec> specs = catalog.all().stream().limit(6).toList();
        List<String> schemas = ToolSchemas.of(specs);

        assertThat(schemas).hasSameSizeAs(specs);
        for (String schema : schemas) {
            JsonNode node = MAPPER.readTree(schema);
            assertThat(node.path("type").asText()).isEqualTo("function");
            JsonNode fn = node.path("function");
            assertThat(fn.path("name").asText()).isNotBlank();
            assertThat(fn.path("description").asText()).isNotBlank();
            JsonNode params = fn.path("parameters");
            assertThat(params.path("type").asText()).isEqualTo("object");
            assertThat(params.path("required").isArray()).isTrue();

            // 每个参数都必须是 JSON Schema 认识的类型，不能把自由文本原样塞进去
            params.path("properties").fields().forEachRemaining(e -> {
                String type = e.getValue().path("type").asText();
                assertThat(List.of("string", "integer", "number", "boolean", "array", "object"))
                        .as("参数 %s 的类型 %s 不合法", e.getKey(), type)
                        .contains(type);
            });
        }
    }

    @Test
    void refundToolExposesAmountAsNumber() throws Exception {
        ToolCatalog catalog = new ToolCatalog();
        ToolSpec refund = catalog.find(ToolCatalog.REFUND_EXECUTE).orElseThrow();
        JsonNode root = MAPPER.readTree(ToolSchemas.one(refund));

        String amountType = root.path("function").path("parameters").path("properties")
                .path("amount").path("type").asText();
        assertThat(amountType)
                .as("金额必须是 number，否则模型会把 129.0 当字符串返回")
                .isIn("number", "integer");
    }

    @Test
    void nativeToolCallsAreNormalisedWithoutDoubleEscaping() throws Exception {
        String body = """
                [{"id":"c1","type":"function","function":{"name":"refund.execute",
                  "arguments":"{\\"orderId\\":\\"A100\\",\\"amount\\":129.5}"}}]
                """;
        JsonNode calls = MAPPER.readTree(body);
        String rendered = OpenAiCompatLlmPort.normalizeToolCalls(calls);

        assertThat(rendered).startsWith("\nTOOL_CALL {");
        assertThat(rendered).doesNotContain("\\\"orderId\\\"");
        assertThat(rendered).contains("\"orderId\":\"A100\"");
        assertThat(rendered).contains("\"amount\":129.5");

        // 归一化之后必须能被内部解析器原样读回来——这是两条协议对齐的证明
        var parsed = io.github.tangbailin.novaorg.policy.ToolRequestParser
                .parse(rendered, "compensation_specialist");
        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).toolName()).isEqualTo("refund.execute");
        assertThat(String.valueOf(parsed.get(0).arguments().get("orderId"))).isEqualTo("A100");
    }

    @Test
    void malformedOrEmptyArgumentsDegradeInsteadOfThrowing() throws Exception {
        JsonNode broken = MAPPER.readTree("""
                [{"function":{"name":"order.detail","arguments":"{not json"}},
                 {"function":{"name":"","arguments":"{}"}},
                 {"function":{"name":"audit.query"}}]
                """);
        String rendered = OpenAiCompatLlmPort.normalizeToolCalls(broken);

        var parsed = io.github.tangbailin.novaorg.policy.ToolRequestParser
                .parse(rendered, "compliance_officer");
        // 名字为空的那条要被丢掉；坏 arguments 和缺 arguments 降级成 {}，不抛异常
        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(0).arguments()).isEmpty();
        assertThat(parsed.get(1).arguments()).isEmpty();
    }

    @Test
    void requestCarriesToolsThroughTheBuilder() {
        LlmRequest request = LlmRequest.builder()
                .system("s").user("u")
                .tools(List.of("{\"type\":\"function\"}"))
                .build();
        assertThat(request.tools()).hasSize(1);

        // 不传工具时必须退化成纯文本对话，不能变成空数组发出去
        LlmRequest plain = LlmRequest.of("s", "u");
        assertThat(plain.tools()).isEmpty();
        assertThat(plain.withMaxOutputTokens(64).tools()).isEmpty();
    }
}
