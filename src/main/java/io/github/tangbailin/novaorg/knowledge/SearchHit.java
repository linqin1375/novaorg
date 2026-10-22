package io.github.tangbailin.novaorg.knowledge;

/** A retrieved chunk plus the scores that produced the ranking. */
public record SearchHit(DocumentChunk chunk, double score, double bm25, double vector, double rerank) {
}
