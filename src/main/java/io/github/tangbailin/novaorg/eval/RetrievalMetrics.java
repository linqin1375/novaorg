package io.github.tangbailin.novaorg.eval;

import java.util.List;

/**
 * 一个检索配置的指标。
 *
 * <p>为什么是这几个指标：
 * <ul>
 *   <li><b>recall@k</b> —— 在 k 条结果里有没有出现"正确文档"。RAG 里下游模型只能看到
 *       检索出来的那几条，检索不到就等于这个知识不存在，所以召回比精确率更致命。</li>
 *   <li><b>MRR</b> —— 第一个正确结果的倒数排名。回答"正确文档排在第几"，@3 相同但
 *       MRR 更高意味着更容易被模型优先读到（也更容易被引用溯源命中）。</li>
 *   <li><b>按难度分组</b> —— 整体指标会被 easy 用例灌水。hard 组才是这次改造的目标，
 *       所以必须单独报。</li>
 * </ul>
 */
public record RetrievalMetrics(
        String embeddingId,
        int total,
        double recallAt1,
        double recallAt3,
        double recallAt5,
        double mrr,
        int hardTotal,
        double hardRecallAt3,
        int easyTotal,
        double easyRecallAt3,
        List<RetrievalResult> results
) {
    public String tableRow() {
        return "| %-42s | %.1f%% | %.1f%% | %.1f%% | %.3f | %.1f%% (%d) |".formatted(
                embeddingId, recallAt1 * 100, recallAt3 * 100, recallAt5 * 100, mrr,
                hardRecallAt3 * 100, hardTotal);
    }
}
