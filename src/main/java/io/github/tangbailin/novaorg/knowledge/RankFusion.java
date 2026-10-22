package io.github.tangbailin.novaorg.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion（RRF）。
 *
 * <p>旧的融合方式是「归一化分数后加权求和」。它有两个毛病：
 * <ol>
 *   <li>BM25 和余弦相似度的分数量纲完全不同，归一化只是把最高分拉到 1，
 *       第二名的相对位置仍然是任意的；</li>
 *   <li>某个通道的分数分布一变（比如语料扩容），权重就得重调，等于把一个
 *       不可解释的调参项写死进了代码。</li>
 * </ol>
 *
 * <p>RRF 只用<b>排名</b>，不用分数：{@code score(d) = Σ_i 1/(k + rank_i(d))}。
 * 它没有需要调的权重，对量纲免疫，是工业界检索融合的默认答案（Cormack et al., 2009），
 * 也是 ES / Weaviate / Vespa 的默认混合检索实现。
 *
 * <p>注意 RRF 只负责<b>召回</b>：它把两个通道都认为相关的文档提到前面。真正的排序交给
 * 后面的 rerank 阶段，这也是「召回-精排」两阶段的正确分工。
 */
public final class RankFusion {

    /** 经验值 60，来自原论文；几乎不影响结果，只是把排名差异平滑掉。 */
    public static final int DEFAULT_K = 60;

    private RankFusion() {
    }

    /**
     * @param rankings 每个元素是一个通道的文档索引排名（越靠前越相关）
     * @return 文档索引 -> RRF 分数
     */
    public static Map<Integer, Double> fuse(List<List<Integer>> rankings) {
        return fuse(rankings, DEFAULT_K);
    }

    public static Map<Integer, Double> fuse(List<List<Integer>> rankings, int k) {
        Map<Integer, Double> scores = new HashMap<>();
        for (List<Integer> ranking : rankings) {
            for (int rank = 0; rank < ranking.size(); rank++) {
                int doc = ranking.get(rank);
                scores.merge(doc, 1.0 / (k + rank + 1.0), Double::sum);
            }
        }
        return scores;
    }

    /** 按 RRF 分数降序取前 n 个文档索引。 */
    public static List<Integer> top(Map<Integer, Double> fused, int n) {
        List<Map.Entry<Integer, Double>> entries = new ArrayList<>(fused.entrySet());
        entries.sort(Comparator.<Map.Entry<Integer, Double>>comparingDouble(Map.Entry::getValue)
                .reversed()
                .thenComparingInt(Map.Entry::getKey));
        List<Integer> out = new ArrayList<>(Math.min(n, entries.size()));
        for (int i = 0; i < entries.size() && i < n; i++) {
            out.add(entries.get(i).getKey());
        }
        return out;
    }
}
