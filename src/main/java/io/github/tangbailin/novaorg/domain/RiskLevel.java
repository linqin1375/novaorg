package io.github.tangbailin.novaorg.domain;

/**
 * Risk of a tool call. HIGH risk tools are never executed inline: they must pass an
 * approval gate, which is exactly how a company treats refunds, payouts and deployments.
 */
public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH;

    public boolean requiresApproval() {
        return this == HIGH;
    }
}
