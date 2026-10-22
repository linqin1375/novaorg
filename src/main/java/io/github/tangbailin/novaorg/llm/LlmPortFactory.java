package io.github.tangbailin.novaorg.llm;

/** Optional SDKs contribute a factory; the core configuration owns the single LlmPort bean. */
@FunctionalInterface
public interface LlmPortFactory {
    LlmPort create();
}
