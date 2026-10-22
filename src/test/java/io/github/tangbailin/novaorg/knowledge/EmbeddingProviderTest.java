package io.github.tangbailin.novaorg.knowledge;
import io.github.tangbailin.novaorg.support.StubProvider;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class EmbeddingProviderTest {
    @Test void indexesAreRespectedWhenProviderReturnsVectorsOutOfOrderAndCacheIsSafe() throws Exception {
        try(var server=new StubProvider("{\"data\":[{\"index\":1,\"embedding\":[0,3]},{\"index\":0,\"embedding\":[2,0]}],\"usage\":{\"prompt_tokens\":17}}")) {
            var embedding=new OpenAiCompatEmbeddingModel(server.url(),"","learned-test",Duration.ofSeconds(2),2);
            var vectors=embedding.embedAll(List.of("first","second","first"));
            assertThat(vectors.get(0)).containsExactly(1,0);assertThat(vectors.get(1)).containsExactly(0,1);
            vectors.get(0)[0]=9;
            assertThat(embedding.embed("first")).containsExactly(1,0);
            assertThat(server.requests).hasSize(1);
            assertThat(server.requests.get(0).path("dimensions").asInt()).isEqualTo(2);
            assertThat(embedding.usageStatistics()).containsEntry("httpRequests",1L).containsEntry("inputTokens",17L).containsEntry("inputTexts",2L);
            assertThat(embedding.isSemantic()).isTrue();
        }
    }
    @Test void wrongDimensionsFailWithoutReturningHashVectors() throws Exception {
        try(var server=new StubProvider("{\"data\":[{\"index\":0,\"embedding\":[1,2,3]}]}")) {
            var embedding=new OpenAiCompatEmbeddingModel(server.url(),"","test",Duration.ofSeconds(2),2);
            assertThatThrownBy(()->embedding.embed("query")).hasMessageContaining("dimension mismatch");
        }
    }
    @Test void duplicateResponseIndexesAndZeroVectorsAreRejected() throws Exception {
        try(var server=new StubProvider("{\"data\":[{\"index\":0,\"embedding\":[1,0]},{\"index\":0,\"embedding\":[0,1]}]}",
                "{\"data\":[{\"index\":0,\"embedding\":[0,0]}]}")) {
            var embedding=new OpenAiCompatEmbeddingModel(server.url(),"","test",Duration.ofSeconds(2),2);
            assertThatThrownBy(()->embedding.embedAll(List.of("a","b"))).hasMessageContaining("index");
            assertThatThrownBy(()->embedding.embed("c")).hasMessageContaining("Zero");
        }
    }
}
