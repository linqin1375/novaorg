package io.github.tangbailin.novaorg.knowledge;
import io.github.tangbailin.novaorg.domain.Citation;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class EvidenceReferencesTest {
    private final List<Citation> sources=List.of(new Citation("first.md","first","policy",0,1),
            new Citation("second.md","second","policy",3,1));
    @Test void stableIdentifiersDoNotDependOnRetrievalRankOrCallNumber() {
        var checked=EvidenceReferences.check("依据 [second.md#3]，结论如此。",sources,true);
        assertThat(checked.docs()).containsExactly("second.md");assertThat(checked.invalid()).isZero();
        var reversed=List.of(sources.get(1),sources.get(0));
        assertThat(EvidenceReferences.check("依据 [second.md#3]",reversed,true).docs()).containsExactly("second.md");
    }
    @Test void forgedOrReusedNumberReferencesCannotPassLiveCitationValidation() {
        var checked=EvidenceReferences.check("依据 [missing.md#0] 和 [1]",sources,true);
        assertThat(checked.invalid()).isEqualTo(2);assertThat(checked.valid()).isZero();
    }
}
