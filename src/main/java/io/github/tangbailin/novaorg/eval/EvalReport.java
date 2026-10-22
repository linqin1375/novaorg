package io.github.tangbailin.novaorg.eval;

import java.util.List;

/** Aggregate evaluation report: the numbers that go into the README and the resume bullet. */
public record EvalReport(
        String generatedAt,
        String llm,
        String embedding,
        int total,
        int passed,
        double passRate,
        double routingAccuracy,
        double toolCompliance,
        double citationHitRate,
        int permissionViolations,
        long avgLatencyMs,
        int avgTokens,
        double totalCostUsd,
        /** 标注为"必须检出注入"的用例数，以及其中真的被检出的数量。 */
        int injectionExpected,
        int injectionDetected,
        double injectionDetectionRate,
        /**
         * 误报：正常业务输入被判定为注入。
         *
         * <p>必须报这个数，否则"检出率 100%"是耍流氓——把检测器调成"什么都算注入"
         * 也能拿到 100%，而那样会把真实客诉全部误伤。
         */
        int injectionFalsePositives,
        double injectionFalsePositiveRate,
        List<EvalResult> cases,
        int modelToolRequests,
        int successfulModelTools,
        double modelToolSuccessRate,
        double answerCitationHitRate,
        double taskCompletionRate,
        boolean usageEstimated,
        java.util.Map<String,String> metadata
) {
}
