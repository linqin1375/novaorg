package io.github.tangbailin.novaorg.eval;

import java.util.List;

/** 单条检索用例的结果，保留排名便于人工核查"为什么没召回"。 */
public record RetrievalResult(
        String caseId,
        String query,
        List<String> expectDocs,
        /** 实际返回的文档 id，按排名顺序。 */
        List<String> retrievedDocs,
        int rankOfFirstHit,
        String category,
        String difficulty
) {
    public boolean hitAt(int k) {
        return rankOfFirstHit > 0 && rankOfFirstHit <= k;
    }

    public double reciprocalRank() {
        return rankOfFirstHit > 0 ? 1.0 / rankOfFirstHit : 0.0;
    }
}
