package io.github.tangbailin.novaorg.metrics;

import io.github.tangbailin.novaorg.domain.Complexity;

/**
 * Service-level objectives. Small tasks get a tight budget, big ones get more room -
 * but no task is allowed to run unbounded. When a budget is blown the runtime degrades
 * (truncates context, caps retries) instead of silently burning money.
 */
public record SlaPolicy(long maxLatencyMs, int maxTokens, double maxCostUsd, int maxRetries) {

    public static SlaPolicy forComplexity(Complexity complexity) {
        return switch (complexity) {
            case SIMPLE -> new SlaPolicy(15_000L, 4_000, 0.05, 1);
            case MODERATE -> new SlaPolicy(30_000L, 8_000, 0.12, 2);
            case COMPLEX -> new SlaPolicy(60_000L, 16_000, 0.30, 3);
        };
    }

    public boolean latencyExceeded(long actualMs) {
        return actualMs > maxLatencyMs;
    }

    public boolean tokensExceeded(int actualTokens) {
        return actualTokens > maxTokens;
    }

    public boolean costExceeded(double actualCostUsd) {
        return actualCostUsd > maxCostUsd;
    }
}
