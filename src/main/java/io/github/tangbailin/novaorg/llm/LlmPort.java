package io.github.tangbailin.novaorg.llm;

/**
 * The only model-facing interface in the codebase.
 *
 * <p>Two implementations ship with the project:
 * <ul>
 *   <li>{@link DemoLlmPort} - deterministic, offline, zero credentials. It makes the project
 *       clone-and-run and makes the evaluation suite reproducible.</li>
 *   <li>{@link OpenAiCompatLlmPort} - any OpenAI-compatible endpoint (DeepSeek, Qwen, Ollama,
 *       vLLM, ...). Switching backends changes one property.</li>
 * </ul>
 */
public interface LlmPort {

    LlmResponse complete(LlmRequest request);

    String id();

    default boolean isLive() {
        return false;
    }
}
