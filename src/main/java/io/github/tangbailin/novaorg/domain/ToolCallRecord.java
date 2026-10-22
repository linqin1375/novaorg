package io.github.tangbailin.novaorg.domain;

/**
 * One auditable tool invocation with its policy outcome.
 *
 * @param approvalRequired the call was refused <b>only</b> because a human must release it
 * @param repeated         an identical call was already made in this step (deduplicated)
 */
public record ToolCallRecord(
        String stepId,
        String roleId,
        String toolName,
        String arguments,
        boolean allowed,
        String denyReason,
        boolean executed,
        boolean approvalRequired,
        boolean repeated,
        String idempotencyKey,
        CallOrigin origin,
        long latencyMs,
        String payloadPreview
) {
}
