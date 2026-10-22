package io.github.tangbailin.novaorg.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct tests for the two pure helpers that everything else leans on but that were only ever
 * exercised indirectly.
 *
 * <p>Both deserve their own coverage because their failure modes are silent rather than loud:
 * <ul>
 *   <li>{@link StructuredOutputParser#extractJson} turns a free-form model answer into the plan.
 *       A model that wraps JSON in prose or a fence is normal - if the parser gives up, the
 *       planner quietly degrades to a single-role plan and the run still "succeeds".</li>
 *   <li>{@link DemoLlmPort#estimateTokens} is the fallback every token, cost and SLA number is
 *       built on. It is also the adapter's fallback when a provider reports no usage, so it has to
 *       stay sane for null and empty input.</li>
 * </ul>
 */
class StructuredOutputParserTest {

    @Test
    void extractsAPlainJsonObject() {
        assertThat(StructuredOutputParser.extractJson("{\"a\":1}")).isEqualTo("{\"a\":1}");
    }

    @Test
    void extractsJsonOutOfSurroundingProse() {
        String raw = """
                好的，我的规划如下：

                {"rationale":"跨部门","complexity":"COMPLEX","roles":"a,b"}

                希望有帮助。
                """;

        assertThat(StructuredOutputParser.extractJson(raw))
                .isEqualTo("{\"rationale\":\"跨部门\",\"complexity\":\"COMPLEX\",\"roles\":\"a,b\"}");
    }

    @Test
    void stripsMarkdownFences() {
        String raw = """
                ```json
                {"rationale":"x","complexity":"SIMPLE","roles":"a"}
                ```
                """;

        assertThat(StructuredOutputParser.extractJson(raw))
                .isEqualTo("{\"rationale\":\"x\",\"complexity\":\"SIMPLE\",\"roles\":\"a\"}");
    }

    @Test
    void handlesNestedObjectsAndArrays() {
        assertThat(StructuredOutputParser.extractJson("{\"a\":{\"b\":[1,2]},\"c\":3}"))
                .isEqualTo("{\"a\":{\"b\":[1,2]},\"c\":3}");
    }

    @Test
    void extractsATopLevelArray() {
        assertThat(StructuredOutputParser.extractJson("[{\"a\":1},{\"b\":2}]"))
                .isEqualTo("[{\"a\":1},{\"b\":2}]");
    }

    /**
     * The reason the parser tracks string state instead of trusting brace counting: a rationale
     * containing braces is one of the most likely real-world outputs, and a naive scanner would
     * truncate the JSON right there and lose the plan.
     */
    @Test
    void ignoresBracesAndBracketsInsideStrings() {
        String json = "{\"rationale\":\"使用 {占位符} 与 [1] 引用\",\"roles\":\"a\"}";

        assertThat(StructuredOutputParser.extractJson("前缀 " + json + " 后缀")).isEqualTo(json);
    }

    @Test
    void handlesEscapedQuotesInsideStrings() {
        String json = "{\"rationale\":\"他说 \\\"可以\\\"\",\"roles\":\"a\"}";

        assertThat(StructuredOutputParser.extractJson(json)).isEqualTo(json);
    }

    @Test
    void returnsEmptyWhenThereIsNothingToExtract() {
        assertThat(StructuredOutputParser.extractJson(null)).isEmpty();
        assertThat(StructuredOutputParser.extractJson("")).isEmpty();
        assertThat(StructuredOutputParser.extractJson("没有任何结构化输出")).isEmpty();
        assertThat(StructuredOutputParser.extractJson("{未闭合的 JSON")).isEmpty();
    }

    // ---------------------------------------------------------------- token estimation

    @Test
    void estimatesNothingForEmptyInput() {
        assertThat(DemoLlmPort.estimateTokens(null)).isZero();
        assertThat(DemoLlmPort.estimateTokens("")).isZero();
    }

    /**
     * CJK is worth roughly 0.7 tokens per character and ASCII roughly 0.25 - so for a comparable
     * character count the Chinese text must not be costed as if it were English.
     */
    @Test
    void cjkCostsMorePerCharacterThanAscii() {
        String chinese = "超时未发货赔付标准";
        String ascii = "timeout-ship-refund";

        assertThat(DemoLlmPort.estimateTokens(chinese))
                .isGreaterThan(DemoLlmPort.estimateTokens(ascii));
    }

    @Test
    void estimatesMixedTextAndScalesWithLength() {
        String text = "订单 A100 超时赔付 standard";
        int tokens = DemoLlmPort.estimateTokens(text);

        assertThat(tokens).isGreaterThan(0);
        assertThat(DemoLlmPort.estimateTokens(text + text)).isGreaterThan(tokens);
    }
}
