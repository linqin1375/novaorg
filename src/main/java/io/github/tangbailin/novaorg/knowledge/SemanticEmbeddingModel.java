package io.github.tangbailin.novaorg.knowledge;

/**
 * 本地人工特征哈希与概念增强（离线、确定性、零依赖；不是训练过的语义模型）。
 *
 * <p>和 {@link HashingEmbeddingModel} 的区别只有一个，但很关键：它在哈希之前先做
 * <b>概念归一化</b>。「钱能退回来吗」和「退款」在词面上零重叠，但都会命中
 * {@code #REFUND} 概念标记，于是两个向量在这一维上直接对齐。这是纯哈希做不到的，
 * 这是人工规则提供的额外信号，其收益需要独立实验验证。
 *
 * <p>向量由三部分叠加：
 * <ol>
 *   <li><b>字词特征</b>：中文单字 + 双字，英文数字整词，长词加权（和哈希模型一致）；</li>
 *   <li><b>概念特征</b>：{@link Synonyms} 命中后注入 {@code #CONCEPT} 标记，权重最高；</li>
 *   <li><b>字符三元组</b>：对中文错别字、拆字、空格插入有一定鲁棒性。</li>
 * </ol>
 *
 * <p>刻意不叫 embedding 模型：它不是神经网络。它的定位是"在没有 API key 时，让向量通道
 * 比词面匹配多一个维度的信号"，并且这个信号是可解释、可回归测试的。真实语义能力由
 * {@link OpenAiCompatEmbeddingModel} 提供，两者实现同一个 {@link EmbeddingPort}，
 * 切换只改一行配置，评测报告会把两者的指标并排打出来。
 */
public final class SemanticEmbeddingModel implements EmbeddingPort {

    private static final float LEXICAL_SCALE = 1.0f;
    private static final float TRIGRAM_WEIGHT = 0.35f;

    private final int dimensions;
    private final float conceptWeight;

    public SemanticEmbeddingModel() {
        this(1024);
    }

    public SemanticEmbeddingModel(int dimensions) {
        this(dimensions, 0.6f);
    }

    /**
     * @param conceptWeight 概念标记的权重。它同时是"语义信号的放大器"和"噪音源"：
     *                      太低则口语改写拉不回来，太高则向量被概念维度主导、丢掉词面区分度。
     *                      0.6 是在 60 条用例上扫出来的（0.0/0.6/1.0/1.8/2.6），见 docs/EVAL.md。
     */
    public SemanticEmbeddingModel(int dimensions, float conceptWeight) {
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions must be positive");
        }
        this.dimensions = dimensions;
        this.conceptWeight = conceptWeight;
    }

    @Override
    public String id() {
        return "local-feature-hashing-concepts-" + dimensions + "d";
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public float[] embed(String text) {
        float[] v = new float[dimensions];
        if (text == null || text.isBlank()) {
            return v;
        }

        for (String token : Tokenizer.tokens(text)) {
            add(v, token, LEXICAL_SCALE * weight(token));
        }
        for (String concept : Synonyms.conceptTokens(text)) {
            add(v, concept, conceptWeight);
        }
        for (String trigram : trigrams(text)) {
            add(v, trigram, TRIGRAM_WEIGHT);
        }
        return HashingEmbeddingModel.normalize(v);
    }

    private void add(float[] v, String token, float weight) {
        int h = token.hashCode();
        int idx = Math.floorMod(h, dimensions);
        // 有符号哈希，避免只累加质量导致的向量偏移
        float sign = ((h >>> 16) & 1) == 0 ? 1f : -1f;
        v[idx] += sign * weight;
    }

    private static float weight(String token) {
        int len = token.length();
        if (token.startsWith("#")) {
            return 1.0f;
        }
        if (len >= 4) {
            return 1.6f;
        }
        if (len == 3) {
            return 1.3f;
        }
        if (len == 2) {
            return 1.0f;
        }
        char c = token.charAt(0);
        if ("的了和是".indexOf(c) >= 0) {
            return 0.15f;
        }
        return 0.55f;
    }

    /** 只对 CJK 连续片段生成三元组，避免给 ASCII 制造噪音。 */
    static java.util.List<String> trigrams(String text) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Tokenizer.isCjk(c)) {
                run.append(c);
            } else if (run.length() > 0) {
                emitTrigrams(run, out);
            }
        }
        emitTrigrams(run, out);
        return out;
    }

    private static void emitTrigrams(StringBuilder run, java.util.List<String> out) {
        if (run.length() >= 3) {
            for (int i = 0; i + 3 <= run.length(); i++) {
                out.add("§" + run.substring(i, i + 3));
            }
        }
        run.setLength(0);
    }
}
