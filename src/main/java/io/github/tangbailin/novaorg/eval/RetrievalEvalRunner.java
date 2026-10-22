package io.github.tangbailin.novaorg.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.tangbailin.novaorg.knowledge.KnowledgeBase;
import io.github.tangbailin.novaorg.knowledge.SearchHit;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;

/**
 * Document-level retrieval evaluation with fixed query/expected-document pairs.
 * Synthetic labels still require human review. Hit@k is not answer correctness.
 * Query provenance and tuning/holdout boundaries are recorded in eval/provenance.json.
 */
public final class RetrievalEvalRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final String DEFAULT_CASES = "eval/retrieval-cases.json";

    private final KnowledgeBase knowledgeBase;
    /** 评测取多深的候选；recall@5 需要至少 5 条。 */
    private final int depth;

    public RetrievalEvalRunner(KnowledgeBase knowledgeBase) {
        this(knowledgeBase, 5);
    }

    public RetrievalEvalRunner(KnowledgeBase knowledgeBase, int depth) {
        this.knowledgeBase = knowledgeBase;
        this.depth = depth;
    }

    public List<RetrievalCase> loadCases(String location) {
        try (InputStream in = new ClassPathResource(location).getInputStream()) {
            return MAPPER.readValue(in, new TypeReference<List<RetrievalCase>>() {
            });
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read retrieval cases: " + location, e);
        }
    }

    public RetrievalMetrics run(List<RetrievalCase> cases) {
        // 全库检索：评测衡量的是"能不能找对文档"，不是"权限隔离对不对"
        List<String> all = knowledgeBase.namespaces();
        List<RetrievalResult> results = new ArrayList<>(cases.size());

        for (RetrievalCase c : cases) {
            // 先多取一些 chunk，再按文档去重：地面真值是文档级的，同一份文档的不同段落
            // 不该把榜单占满——那既高估了命中，也挤掉了别的候选。
            List<SearchHit> hits = knowledgeBase.search(c.query(), all, depth * 4);
            List<String> docs = distinctDocs(hits);
            docs = docs.size() > depth ? docs.subList(0, depth) : docs;
            int rank = 0;
            for (int i = 0; i < docs.size(); i++) {
                if (c.expectDocs().contains(docs.get(i))) {
                    rank = i + 1;
                    break;
                }
            }
            results.add(new RetrievalResult(c.id(), c.query(), c.expectDocs(), List.copyOf(docs), rank,
                    c.category(), c.difficulty()));
        }
        return aggregate(results);
    }

    /** 按文档去重，保留该文档排名最高的那个 chunk 的位置。 */
    static List<String> distinctDocs(List<SearchHit> hits) {
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (SearchHit h : hits) {
            seen.add(h.chunk().docId());
        }
        return List.copyOf(seen);
    }

    private RetrievalMetrics aggregate(List<RetrievalResult> results) {
        int total = results.size();
        int at1 = 0;
        int at3 = 0;
        int at5 = 0;
        double rr = 0;
        for (RetrievalResult r : results) {
            if (r.hitAt(1)) {
                at1++;
            }
            if (r.hitAt(3)) {
                at3++;
            }
            if (r.hitAt(5)) {
                at5++;
            }
            rr += r.reciprocalRank();
        }

        List<RetrievalResult> hard = results.stream().filter(r -> "hard".equalsIgnoreCase(r.difficulty())).toList();
        List<RetrievalResult> easy = results.stream().filter(r -> "easy".equalsIgnoreCase(r.difficulty())).toList();

        return new RetrievalMetrics(
                knowledgeBase.embeddingId(),
                total,
                ratio(at1, total), ratio(at3, total), ratio(at5, total),
                total == 0 ? 0 : round(rr / total),
                hard.size(), ratio((int) hard.stream().filter(r -> r.hitAt(3)).count(), hard.size()),
                easy.size(), ratio((int) easy.stream().filter(r -> r.hitAt(3)).count(), easy.size()),
                List.copyOf(results));
    }

    /** 按业务分类再切一刀，用来定位"检索差在哪个域"。 */
    public Map<String, Double> recallAt3ByCategory(RetrievalMetrics metrics) {
        Map<String, int[]> acc = new LinkedHashMap<>();
        for (RetrievalResult r : metrics.results()) {
            int[] v = acc.computeIfAbsent(r.category(), k -> new int[2]);
            v[0] += r.hitAt(3) ? 1 : 0;
            v[1]++;
        }
        Map<String, Double> out = new LinkedHashMap<>();
        acc.forEach((k, v) -> out.put(k, ratio(v[0], v[1])));
        return out;
    }

    /**
     * 生成"改造前后"对照报告。
     *
     * @param baseline 旧实现（例如纯字面哈希索引）的指标
     * @param candidate 新实现（概念增强 + RRF + 精排）的指标
     */
    public static String compareMarkdown(RetrievalMetrics baseline, RetrievalMetrics candidate,
                                         Map<String, Double> baselineByCat,
                                         Map<String, Double> candidateByCat) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 检索层评测报告\n\n");
        sb.append("## 结论\n\n");
        sb.append("| 指标 | 旧实现（字面哈希 + 加权求和） | 新实现（概念增强 + RRF + 精排） | 变化 |\n");
        sb.append("|---|---|---|---|\n");
        row(sb, "recall@1", baseline.recallAt1(), candidate.recallAt1());
        row(sb, "recall@3", baseline.recallAt3(), candidate.recallAt3());
        row(sb, "recall@5", baseline.recallAt5(), candidate.recallAt5());
        sb.append("| MRR | %.3f | %.3f | %+.3f |\n".formatted(
                baseline.mrr(), candidate.mrr(), candidate.mrr() - baseline.mrr()));
        row(sb, "hard 组 recall@3", baseline.hardRecallAt3(), candidate.hardRecallAt3());
        row(sb, "easy 组 recall@3", baseline.easyRecallAt3(), candidate.easyRecallAt3());

        sb.append("\n## 分业务域 recall@3\n\n");
        sb.append("| 业务域 | 旧实现 | 新实现 | 变化 |\n|---|---|---|---|\n");
        for (String cat : candidateByCat.keySet()) {
            double a = baselineByCat.getOrDefault(cat, 0.0);
            double b = candidateByCat.get(cat);
            sb.append("| ").append(cat).append(" | ").append(pct(a)).append(" | ").append(pct(b))
                    .append(" | ").append(delta(b - a)).append(" |\n");
        }

        sb.append("\n## 失败用例（新实现仍未召回）\n\n");
        List<RetrievalResult> misses = candidate.results().stream()
                .filter(r -> !r.hitAt(3)).toList();
        if (misses.isEmpty()) {
            sb.append("无。\n");
        } else {
            sb.append("| 用例 | 查询 | 期望文档 | 实际 Top3 |\n|---|---|---|---|\n");
            for (RetrievalResult r : misses) {
                sb.append("| ").append(r.caseId())
                        .append(" | ").append(escape(r.query()))
                        .append(" | ").append(String.join(",", r.expectDocs()))
                        .append(" | ").append(String.join(",", r.retrievedDocs()))
                        .append(" |\n");
            }
        }

        sb.append("\n## 方法说明\n\n");
        sb.append("- 用例集：60 条历史合成或改写查询，原始投诉来源尚未逐条核实，")
                .append("hard 为难度标签，不保证零词面重叠。\n");
        sb.append("- 地面真值：每条查询标注 1~2 份「能回答它」的制度文档，预期文档由测试数据指定，仍需独立人工复核。\n");
        sb.append("- 两个实现的差别只有向量通道与融合方式，其余（切块、BM25 参数、rerank 特征）完全一致，")
                .append("因此差值可以归因到这两处改动。\n");
        return sb.toString();
    }

    public void write(String markdown, Path path) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(path, markdown, StandardCharsets.UTF_8);
    }

    private static void row(StringBuilder sb, String name, double a, double b) {
        sb.append("| ").append(name).append(" | ").append(pct(a)).append(" | ").append(pct(b))
                .append(" | ").append(delta(b - a)).append(" |\n");
    }

    private static String pct(double v) {
        return "%.1f%%".formatted(v * 100);
    }

    private static String delta(double v) {
        return "%+.1f pt".formatted(v * 100);
    }

    private static String escape(String s) {
        return s.replace("|", "\\|");
    }

    private static double ratio(int hit, int total) {
        return total == 0 ? 0 : round((double) hit / total);
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
