package io.github.tangbailin.novaorg.eval;

import io.github.tangbailin.novaorg.knowledge.*;
import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Frozen development regression plus separately reported holdout; no tuning from test results. */
class RetrievalEvalTest {
    private RetrievalMetrics run(List<RetrievalCase> cases,Set<InMemoryHybridIndex.Channel> channels,double rerank) {
        var kb=new ClasspathKnowledgeBase(new SemanticEmbeddingModel(1024,0.6f),
                ClasspathKnowledgeBase.DEFAULT_LOCATION,60,20,rerank,channels);
        return new RetrievalEvalRunner(kb).run(cases);
    }
    private Map<String,RetrievalMetrics> ablate(List<RetrievalCase> cases) {
        var runs=new LinkedHashMap<String,RetrievalMetrics>();
        runs.put("BM25",run(cases,EnumSet.of(InMemoryHybridIndex.Channel.LEXICAL),0));
        runs.put("BM25 + feature-hashing",run(cases,EnumSet.of(InMemoryHybridIndex.Channel.LEXICAL,InMemoryHybridIndex.Channel.DENSE),0));
        runs.put("BM25 + feature-hashing + concepts",run(cases,EnumSet.allOf(InMemoryHybridIndex.Channel.class),0));
        runs.put("BM25 + concepts",run(cases,EnumSet.of(InMemoryHybridIndex.Channel.LEXICAL,InMemoryHybridIndex.Channel.CONCEPT),0));
        runs.put("all + heuristic rerank(0.6)",run(cases,EnumSet.allOf(InMemoryHybridIndex.Channel.class),0.6));
        return runs;
    }
    @Test void reportsActualRerankComparisonAndKeepsHoldoutSeparate() throws Exception {
        var loader=new RetrievalEvalRunner(new ClasspathKnowledgeBase(new SemanticEmbeddingModel()));
        var dev=loader.loadCases("eval/retrieval-cases.json");var heldout=loader.loadCases("eval/retrieval-holdout.json");
        assertEquals(60,dev.size(),"Frozen development dataset changed; review its version");
        assertTrue(Collections.disjoint(dev.stream().map(RetrievalCase::query).toList(),heldout.stream().map(RetrievalCase::query).toList()));
        var runs=ablate(dev);var h=ablate(heldout);
        var shipped=runs.get("BM25 + feature-hashing + concepts");
        assertTrue(shipped.recallAt3()>=0.93,"Development retrieval regressed");
        assertTrue(shipped.recallAt5()>=0.95,"Development Hit@5 regressed");
        var out=new StringBuilder("# 检索消融报告\n\n")
                .append("当前使用人工特征哈希和概念表，未测真实神经语义向量。不能由本报告推断真实 embedding 的收益。\n\n")
                .append("旧字段 recallAtK 的含义是 Hit@k：任一预期文档进入前 k 份不同文档。\n")
                .append("开发集 60 条曾参与调参；留出集 20 条为 AI 辅助构造、待人工复核，不用于选权重。\n")
                .append("hard 为难度标签，不保证与制度文字零重叠。详见 eval/provenance.json。\n\n");
        table(out,"开发集",runs);table(out,"留出集（不用于调参）",h);
        var base=runs.get("BM25");
        out.append("\n开发集 hard 命中：").append(Math.round(base.hardRecallAt3()*base.hardTotal())).append("/")
                .append(base.hardTotal()).append(" → ").append(Math.round(shipped.hardRecallAt3()*shipped.hardTotal())).append("/")
                .append(shipped.hardTotal()).append("。小样本结果，不是泛化保证。\n");
        out.append("\n## 开发集未进入 Top3 的用例\n\n| 用例 | 查询 | 预期 | 实际Top3 |\n|---|---|---|---|\n");
        for(var item:shipped.results()) if(!item.hitAt(3)) out.append("| ").append(item.caseId()).append(" | ")
                .append(item.query().replace("|","/")).append(" | ").append(item.expectDocs()).append(" | ")
                .append(item.retrievedDocs().stream().limit(3).toList()).append(" |\n");
        Files.createDirectories(Path.of("reports"));
        Files.writeString(Path.of("reports/retrieval-report.md"),out,StandardCharsets.UTF_8);
    }
    private static void table(StringBuilder out,String title,Map<String,RetrievalMetrics> runs) {
        out.append("## ").append(title).append("\n\n| 配置 | Hit@1 | Hit@3 | Hit@5 | hard Hit@3 | MRR |\n|---|---|---|---|---|---|\n");
        runs.forEach((label,m)->out.append("| ").append(label).append(" | ").append(m.recallAt1()).append(" | ")
                .append(m.recallAt3()).append(" | ").append(m.recallAt5()).append(" | ").append(m.hardRecallAt3())
                .append(" | ").append(m.mrr()).append(" |\n"));
    }
}
