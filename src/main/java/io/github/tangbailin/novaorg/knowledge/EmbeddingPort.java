package io.github.tangbailin.novaorg.knowledge;

import java.util.List;

/**
 * Embedding is a port, not a dependency. The default implementation is a deterministic
 * local feature-hashing model so the project runs (and its evaluation suite is
 * reproducible) with zero API keys; a provider-backed implementation can be dropped in
 * without touching the retrieval code.
 */
public interface EmbeddingPort {

    String id();
    default boolean isSemantic() { return false; }

    int dimensions();

    float[] embed(String text);

    default List<float[]> embedAll(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }
}
