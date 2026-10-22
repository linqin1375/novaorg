package io.github.tangbailin.novaorg.knowledge;

/** A retrievable unit of knowledge, with the provenance needed for citations. */
public record DocumentChunk(
        String docId,
        String title,
        String namespace,
        int chunkIndex,
        String text
) {
    public String citationLabel() {
        return title + "#" + chunkIndex;
    }
}
