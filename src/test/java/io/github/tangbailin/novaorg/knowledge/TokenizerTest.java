package io.github.tangbailin.novaorg.knowledge;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenizerTest {

    @Test
    void emitsCjkBigramsSoThatChineseCanBeMatchedWithoutASegmenter() {
        List<String> tokens = Tokenizer.tokens("赔付标准");
        assertThat(tokens).contains("赔付", "付标", "标准");
        assertThat(tokens).contains("赔", "付", "标", "准");
    }

    @Test
    void keepsAsciiWordsWholeAndLowercasesThem() {
        List<String> tokens = Tokenizer.tokens("Order A100 Price-Protection");
        assertThat(tokens).contains("order", "a100", "price", "protection");
    }

    @Test
    void handlesMixedChineseAndAsciiInOneString() {
        List<String> tokens = Tokenizer.tokens("退款 refund.execute 权限");
        assertThat(tokens).contains("refund", "execute", "退款", "权限");
    }

    @Test
    void blankInputProducesNoTokens() {
        assertThat(Tokenizer.tokens("")).isEmpty();
        assertThat(Tokenizer.tokens(null)).isEmpty();
    }
}
