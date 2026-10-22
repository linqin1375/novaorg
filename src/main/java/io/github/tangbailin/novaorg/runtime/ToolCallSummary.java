package io.github.tangbailin.novaorg.runtime;

/** Compact, serializable view of one tool invocation for the API and the console. */
public record ToolCallSummary(
        String toolName,
        String arguments,
        boolean allowed,
        boolean executed,
        boolean approvalRequired,
        boolean repeated,
        String denyReason,
        String origin,
        String payload
) {
}
