package io.github.tangbailin.novaorg.domain;

/** A traceable source. Every claim an agent makes must be able to point at one of these. */
public record Citation(String docId, String title, String namespace, int chunkIndex, double score) {
}
