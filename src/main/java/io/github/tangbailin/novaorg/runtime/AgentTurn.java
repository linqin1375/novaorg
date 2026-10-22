package io.github.tangbailin.novaorg.runtime;

import io.github.tangbailin.novaorg.llm.LlmToolCall;
import java.util.List;

/** Persisted public execution trace; no API secrets or hidden reasoning. */
public record AgentTurn(int turn, String provider, String answer, List<LlmToolCall> toolRequests,
                        List<String> observations, int inputTokens, int outputTokens,
                        long latencyMs, boolean usageEstimated) {
    public AgentTurn {
        toolRequests = toolRequests == null ? List.of() : List.copyOf(toolRequests);
        observations = observations == null ? List.of() : List.copyOf(observations);
    }
}
