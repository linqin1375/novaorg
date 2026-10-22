package io.github.tangbailin.novaorg.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 两阶段混合检索：<b>召回（BM25 通道 + 向量通道 → RRF 融合）→ 精排（rerank）→ topK</b>。
 *
 * <p>和上一版的区别，也是这一版存在的理由：
 * <ul>
 *   <li><b>融合改成 RRF</b>：不再把两个量纲不同的分数归一化后加权求和。理由见
 *       {@link RankFusion}——加权求和需要一个没有理论依据的权重，RRF 不需要。</li>
 *   <li><b>rerank 从"加一点分"变成独立的第二阶段</b>：旧版把 rerank 分数直接加到融合分上，
 *       等于三个信号糊成一个数，既说不清排序原因，也没法单独调。现在先靠 RRF 召回
 *       {@code rerankDepth} 条候选，再用精排特征重新排序，两阶段各自可解释、可单独评测。</li>
 *   <li><b>两个通道真正互补</b>：BM25 只看原词（保精度：订单号、条款号、商品名），
 *       向量通道额外看概念标记（保召回：口语改写）。旧版两个通道都是词面匹配，
 *       融合收益接近 0，这一点在 {@code docs/EVAL.md} 的对照实验里能直接看到。</li>
 * </ul>
 *
 * <p>复杂度仍是 O(N) 打分 + O(N log N) 排序，语料规模在万级 chunk 以内完全够用；
 * 再大就应该换成 ES / pgvector 的 ANN 索引，接口不变。
 */
public final class InMemoryHybridIndex {

    /** 三个可独立开关的召回通道，用于做消融实验并确认每一路都"值回票价"。 */
    public enum Channel { LEXICAL, DENSE, CONCEPT }

    /** 精排阶段重新排序的候选条数：召回宁多勿漏，精排宁准勿滥。 */
    private static final int DEFAULT_RERANK_DEPTH = 20;

    private final EmbeddingPort embeddingPort;
    private final int rrfK;
    private final int rerankDepth;
    /** 最终排序里精排分与 RRF 分的混合比例。 */
    private final double rerankWeight;
    private final Set<Channel> channels;
    private final double k1 = 1.4;
    private final double b = 0.72;

    private final List<DocumentChunk> chunks = new ArrayList<>();
    private final List<float[]> vectors = new ArrayList<>();
    private final List<Map<String, Integer>> termFreqs = new ArrayList<>();
    private final List<Integer> termCounts = new ArrayList<>();
    private final List<Set<String>> concepts = new ArrayList<>();
    private final Map<String, Integer> docFreq = new HashMap<>();
    private double avgTermCount;

    public InMemoryHybridIndex(EmbeddingPort embeddingPort) {
        this(embeddingPort, RankFusion.DEFAULT_K, DEFAULT_RERANK_DEPTH, 0.0);
    }

    public InMemoryHybridIndex(EmbeddingPort embeddingPort, int rrfK, int rerankDepth, double rerankWeight) {
        this(embeddingPort, rrfK, rerankDepth, rerankWeight, EnumSet.allOf(Channel.class));
    }

    public InMemoryHybridIndex(EmbeddingPort embeddingPort, int rrfK, int rerankDepth, double rerankWeight,
                               Set<Channel> channels) {
        this.embeddingPort = embeddingPort;
        this.rrfK = rrfK;
        this.rerankDepth = rerankDepth;
        this.rerankWeight = rerankWeight;
        this.channels = channels.isEmpty() ? EnumSet.of(Channel.LEXICAL) : EnumSet.copyOf(channels);
    }

    public synchronized void add(List<DocumentChunk> newChunks) {
        var embedded=embeddingPort.embedAll(newChunks.stream().map(DocumentChunk::text).toList());
        for (int chunkIndex=0;chunkIndex<newChunks.size();chunkIndex++) {
            DocumentChunk chunk=newChunks.get(chunkIndex);
            chunks.add(chunk);
            // 概念归一化是 EmbeddingPort 的职责，不是索引的：索引只负责"把文本交给向量端口"。
            // 这样 HashingEmbeddingModel 才能作为干净的对照组（纯词面），
            // 否则两组都吃到概念信号，对照实验就失去意义了。
            vectors.add(embedded.get(chunkIndex));
            Map<String, Integer> tf = new HashMap<>();
            for (String t : Tokenizer.tokens(chunk.text())) {
                tf.merge(t, 1, Integer::sum);
            }
            termFreqs.add(tf);
            termCounts.add(tf.values().stream().mapToInt(Integer::intValue).sum());
            concepts.add(new LinkedHashSet<>(Synonyms.conceptTokens(chunk.text())));
            tf.keySet().forEach(t -> docFreq.merge(t, 1, Integer::sum));
        }
        avgTermCount = termCounts.stream().mapToInt(Integer::intValue).average().orElse(1.0);
    }

    public int size() {
        return chunks.size();
    }

    /** 语料里实际出现过的全部命名空间，供"全库检索"类诊断与评测使用。 */
    public List<String> namespaces() {
        return List.copyOf(new LinkedHashSet<>(chunks.stream().map(DocumentChunk::namespace).toList()));
    }

    public EmbeddingPort embeddingPort() {
        return embeddingPort;
    }

    /**
     * @param namespaces 调用方角色可读的知识命名空间；不在其中的 chunk 对此角色不可见，
     *                   这是权限隔离在检索侧的那一半
     */
    public List<SearchHit> search(String query, List<String> namespaces, int topK) {
        return search(query, namespaces, topK, true);
    }

    public List<SearchHit> search(String query, List<String> namespaces, int topK, boolean rerank) {
        Set<String> allowed = new java.util.HashSet<>(namespaces);
        List<String> queryTerms = Tokenizer.tokens(query);
        Set<String> queryConcepts = new LinkedHashSet<>(Synonyms.conceptTokens(query));
        float[] queryVector = embeddingPort.embed(query);

        List<Integer> candidates = new ArrayList<>();
        double[] bm25 = new double[chunks.size()];
        double[] vector = new double[chunks.size()];
        for (int i = 0; i < chunks.size(); i++) {
            if (!allowed.contains(chunks.get(i).namespace())) {
                continue;
            }
            candidates.add(i);
            bm25[i] = bm25(i, queryTerms);
            vector[i] = HashingEmbeddingModel.cosine(queryVector, vectors.get(i));
        }
        if (candidates.isEmpty()) {
            return List.of();
        }

        // ---- 召回阶段：三个通道各自排名，再用 RRF 融合
        // 通道 1：BM25  —— 词面精确性（订单号、条款号、商品名）
        List<Integer> lexicalRanking = new ArrayList<>(candidates);
        if (!channels.contains(Channel.LEXICAL)) {
            lexicalRanking.clear();
        }
        lexicalRanking.removeIf(i -> bm25[i] <= 0);
        lexicalRanking.sort(Comparator.<Integer>comparingDouble(i -> bm25[i]).reversed()
                .thenComparingInt(i -> i));

        // 通道 2：稠密向量 —— 由 EmbeddingPort 决定是词面还是语义
        List<Integer> denseRanking = new ArrayList<>(candidates);
        if (!channels.contains(Channel.DENSE)) {
            denseRanking.clear();
        }
        denseRanking.removeIf(i -> vector[i] <= 0);
        denseRanking.sort(Comparator.<Integer>comparingDouble(i -> vector[i]).reversed()
                .thenComparingInt(i -> i));

        // 通道 3：概念重合 —— 口语改写与制度用语之间的桥。
        // 单独成一个通道而不是揉进稠密向量：概念信号很"粗"，混进向量会把词面信号稀释掉；
        // 作为独立通道参与 RRF，它只在真的命中概念时才加分，不命中就完全不影响排序。
        List<Integer> conceptRanking = new ArrayList<>(candidates);
        double[] conceptScore = new double[chunks.size()];
        if (channels.contains(Channel.CONCEPT) && !queryConcepts.isEmpty()) {
            for (int i : candidates) {
                conceptScore[i] = conceptOverlap(queryConcepts, concepts.get(i));
            }
            conceptRanking.removeIf(i -> conceptScore[i] <= 0);
            conceptRanking.sort(Comparator.<Integer>comparingDouble(i -> conceptScore[i]).reversed()
                    .thenComparingInt(i -> i));
        } else {
            conceptRanking.clear();
        }

        List<List<Integer>> rankings = new ArrayList<>();
        if (!lexicalRanking.isEmpty()) {
            rankings.add(lexicalRanking);
        }
        if (!denseRanking.isEmpty()) {
            rankings.add(denseRanking);
        }
        if (!conceptRanking.isEmpty()) {
            rankings.add(conceptRanking);
        }
        if (rankings.isEmpty()) {
            return List.of();
        }

        Map<Integer, Double> fused = RankFusion.fuse(rankings, rrfK);
        List<Integer> shortlist = RankFusion.top(fused, rerankDepth);
        if (shortlist.isEmpty()) {
            return List.of();
        }

        // ---- 精排阶段：只看短名单，用精排特征重新排序
        double maxBm25 = candidates.stream().mapToDouble(i -> bm25[i]).max().orElse(1.0);
        double maxVector = candidates.stream().mapToDouble(i -> vector[i]).max().orElse(1.0);
        double maxRrf = shortlist.stream().mapToDouble(i -> fused.getOrDefault(i, 0.0)).max().orElse(1.0);

        List<SearchHit> scored = new ArrayList<>(shortlist.size());
        for (int i : shortlist) {
            double normBm25 = maxBm25 <= 0 ? 0 : bm25[i] / maxBm25;
            double normVector = maxVector <= 0 ? 0 : vector[i] / maxVector;
            double normRrf = maxRrf <= 0 ? 0 : fused.getOrDefault(i, 0.0) / maxRrf;
            double rr = rerank ? rerank(queryTerms, queryConcepts, chunks.get(i).text(), concepts.get(i)) : 0;
            double finalScore = rerank
                    ? rerankWeight * rr + (1 - rerankWeight) * normRrf
                    : normRrf;
            scored.add(new SearchHit(chunks.get(i), finalScore, normBm25, normVector, rr));
        }

        scored.sort(Comparator.comparingDouble(SearchHit::score).reversed()
                .thenComparing(h -> h.chunk().docId())
                .thenComparingInt(h -> h.chunk().chunkIndex()));
        return scored.size() > topK ? List.copyOf(scored.subList(0, topK)) : List.copyOf(scored);
    }

    /** 查询概念在 chunk 概念里的覆盖率，0~1。 */
    private static double conceptOverlap(Set<String> queryConcepts, Set<String> chunkConcepts) {
        if (queryConcepts.isEmpty() || chunkConcepts.isEmpty()) {
            return 0;
        }
        int hit = 0;
        for (String c : queryConcepts) {
            if (chunkConcepts.contains(c)) {
                hit++;
            }
        }
        return (double) hit / queryConcepts.size();
    }

    private double bm25(int docIdx, List<String> queryTerms) {
        Map<String, Integer> tf = termFreqs.get(docIdx);
        int docLen = termCounts.get(docIdx);
        double score = 0;
        Map<String, Integer> seen = new HashMap<>();
        for (String term : queryTerms) {
            seen.merge(term, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : seen.entrySet()) {
            String term = e.getKey();
            Integer f = tf.get(term);
            if (f == null) {
                continue;
            }
            int df = docFreq.getOrDefault(term, 0);
            double idf = Math.log(1 + (chunks.size() - df + 0.5) / (df + 0.5));
            double denom = f + k1 * (1 - b + b * (docLen / Math.max(avgTermCount, 1.0)));
            score += idf * (f * (k1 + 1)) / denom * Math.min(e.getValue(), 3);
        }
        return score;
    }

    /**
     * 精排特征。它不是 cross-encoder（那需要另一个模型），但它比"词面覆盖率"多用了
     * <b>概念重合</b>这一维：查询说"退钱"、文档写"退款"时，词面覆盖率为 0，概念重合为 1。
     * 这两项加权是离线可得的最强信号组合，且每一项都能在评测里单独归因。
     */
    private double rerank(List<String> queryTerms, Set<String> queryConcepts,
                          String text, Set<String> chunkConcepts) {
        Set<String> unique = new LinkedHashSet<>(queryTerms);
        if (unique.isEmpty() && queryConcepts.isEmpty()) {
            return 0;
        }

        double lexical = 0;
        if (!unique.isEmpty()) {
            String lower = text.toLowerCase(Locale.ROOT);
            int hit = 0;
            int longHits = 0;
            for (String term : unique) {
                if (lower.contains(term)) {
                    hit++;
                    if (term.length() >= 2) {
                        longHits++;
                    }
                }
            }
            lexical = 0.6 * ((double) hit / unique.size()) + 0.4 * ((double) longHits / unique.size());
        }

        double semantic = 0;
        if (!queryConcepts.isEmpty()) {
            int overlap = 0;
            for (String c : queryConcepts) {
                if (chunkConcepts.contains(c)) {
                    overlap++;
                }
            }
            semantic = (double) overlap / queryConcepts.size();
        }

        // 标题行/条款号在短文本里信息密度更高，给一点补偿
        double structural = text.length() <= 160 ? 0.05 : 0.0;
        return Math.min(1.0, 0.5 * lexical + 0.45 * semantic + structural);
    }
}
