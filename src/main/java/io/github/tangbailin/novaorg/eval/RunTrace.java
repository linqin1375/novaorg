package io.github.tangbailin.novaorg.eval;

import java.util.List;

/** Flat, gradable view of one workflow run. */
public record RunTrace(
        String status,
        List<String> roles,
        List<String> toolsCalled,
        List<String> toolsExecuted,
        List<String> toolsDenied,
        List<String> citationDocs,
        boolean permissionViolation,
        long latencyMs,
        int tokens,
        double costUsd,
        /** 本次运行是否被 PromptGuard 判定为疑似注入（审计里有对应事件）。 */
        boolean injectionDetected,
        List<String> modelToolsRequested,
        List<String> modelToolsExecuted,
        List<String> answerCitationDocs,
        int invalidCitationReferences,
        int refundCount,
        boolean usageEstimated,
        List<io.github.tangbailin.novaorg.runtime.StepResult> steps,
        String failureReason,
        boolean slaMet,
        List<Long> pendingRefundFen
) {
    public boolean hasDenial() {
        return !toolsDenied.isEmpty();
    }
}
