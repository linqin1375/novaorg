package io.github.tangbailin.novaorg.metrics;

/**
 * Turns token counts into money so cost is a first-class metric instead of a surprise
 * on the invoice. Rates are per 1M tokens and configurable.
 */
public final class CostModel {

    private final double inputPerMillionUsd;
    private final double outputPerMillionUsd;

    public CostModel(double inputPerMillionUsd, double outputPerMillionUsd) {
        this.inputPerMillionUsd = inputPerMillionUsd;
        this.outputPerMillionUsd = outputPerMillionUsd;
    }

    /** A deliberately cheap, provider-agnostic default. */
    public static CostModel defaultModel() {
        return new CostModel(0.30, 1.20);
    }

    public double cost(int inputTokens, int outputTokens) {
        return (inputTokens / 1_000_000.0) * inputPerMillionUsd
                + (outputTokens / 1_000_000.0) * outputPerMillionUsd;
    }

    public double cost(int totalTokens) {
        // assume a 3:1 input:output mix when only the total is known
        return cost((int) (totalTokens * 0.75), (int) (totalTokens * 0.25));
    }
}
