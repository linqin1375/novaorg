package io.github.tangbailin.novaorg.eval;

import java.util.List;

/** Result of one case, with the evidence a reviewer needs to trust the verdict. */
public record EvalResult(
        String caseId,
        String goal,
        boolean passed,
        List<String> failures,
        List<String> expectRoles,
        List<String> actualRoles,
        List<String> expectTools,
        List<String> actualTools,
        List<String> executedTools,
        List<String> deniedTools,
        boolean citationHit,
        boolean permissionViolation,
        long latencyMs,
        int tokens,
        double costUsd,
        String status,
        /** 该用例是否要求"输入必须被检出为疑似注入"。 */
        boolean injectionExpected,
        /** 本次运行是否被 PromptGuard 检出疑似注入。 */
        boolean injectionDetected,
        int modelToolRequests,
        int successfulModelTools,
        boolean answerCitationHit,
        int invalidCitationReferences,
        boolean completed,
        boolean usageEstimated,
        int refundCount,
        List<io.github.tangbailin.novaorg.runtime.StepResult> trajectory,
        boolean slaMet,
        List<Long> pendingRefundFen
) {
}
