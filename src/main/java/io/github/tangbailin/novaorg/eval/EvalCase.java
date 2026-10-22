package io.github.tangbailin.novaorg.eval;

import java.util.List;

/**
 * One evaluation case: a business goal plus the observable behaviours that must hold.
 * Ground truth is expressed only in things the system can be graded on objectively.
 */
public record EvalCase(
        String id,
        String goal,
        String approvalMode,
        /** Roles that must appear in the plan (subset check). */
        List<String> expectRoles,
        /** Roles that must NOT appear (guards against over-staffing). */
        List<String> forbidRoles,
        /** Tools expected to be called successfully somewhere in the run. */
        List<String> expectTools,
        /** Tools that must never execute. */
        List<String> forbidExecutedTools,
        /** At least one citation must come from one of these documents. */
        List<String> expectCitationDocs,
        /** No citation may come from any of these documents (cross-department isolation). */
        List<String> forbidCitationDocs,
        /** The run must end with at least one refused tool call. */
        Boolean expectDenial,
        /** true = no denials at all allowed; null = not checked. */
        Boolean expectNoDenial,
        /** Expected final status when checked (e.g. WAITING_APPROVAL). */
        String expectStatus,
        /**
         * 业务标签（售后 / 合规 / 营销 / 对抗 / 注入 ...）。
         * 用于把汇总指标按场景切开：整体通过率会被简单用例灌水，分场景才看得出问题。
         */
        List<String> tags,
        /**
         * true = 这条输入必须被 PromptGuard 检出并写进审计。
         * 只有"检出"是可客观判定的；"模型有没有被带偏"要另外用行为验证。
         */
        Boolean expectInjection,
        List<String> expectSuccessfulTools,
        List<String> expectModelTools,
        Integer expectRefundCount,
        Boolean requireAnswerCitation,
        List<String> forbidAnswerPhrases,
        Long expectPendingRefundFen
) {
    public List<String> tags() {
        return tags == null ? List.of() : tags;
    }
}
