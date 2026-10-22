package io.github.tangbailin.novaorg.knowledge;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HashingEmbeddingModelTest {

    private final HashingEmbeddingModel model = new HashingEmbeddingModel(256);

    @Test
    void isDeterministicSoEvalRunsAreReproducible() {
        float[] first = model.embed("赔付标准 超时 24 小时");
        float[] second = model.embed("赔付标准 超时 24 小时");
        assertThat(first).containsExactly(second);
    }

    @Test
    void producesUnitVectorsSoCosineSimilarityIsBounded() {
        float[] v = model.embed("订单超时未发货，按实付金额 10% 赔付");
        double norm = 0;
        for (float f : v) {
            norm += (double) f * f;
        }
        assertThat(Math.sqrt(norm)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void similarTextScoresHigherThanUnrelatedText() {
        float[] query = model.embed("超时发货赔付标准");
        float[] related = model.embed("订单超过承诺时效 24 小时未发货的赔付标准");
        float[] unrelated = model.embed("营销短信触达频率限制");

        double relatedScore = HashingEmbeddingModel.cosine(query, related);
        double unrelatedScore = HashingEmbeddingModel.cosine(query, unrelated);

        assertThat(relatedScore).isGreaterThan(unrelatedScore);
    }

    @Test
    void emptyTextDoesNotProduceNaN() {
        float[] v = model.embed("");
        for (float f : v) {
            assertThat(Float.isNaN(f)).isFalse();
        }
    }
}
