package io.github.tangbailin.novaorg.eval;

import io.github.tangbailin.novaorg.knowledge.ClasspathKnowledgeBase;
import io.github.tangbailin.novaorg.knowledge.HashingEmbeddingModel;
import io.github.tangbailin.novaorg.knowledge.InMemoryHybridIndex;
import io.github.tangbailin.novaorg.knowledge.RankFusion;
import io.github.tangbailin.novaorg.knowledge.SemanticEmbeddingModel;

import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Test;

/** 消融实验：逐通道打开，确认每一个召回通道是否都值回票价。 */
class RetrievalDiagnosticTest {

    @Test
    void ablation() {
        var cases = new RetrievalEvalRunner(new ClasspathKnowledgeBase(new SemanticEmbeddingModel()))
                .loadCases(RetrievalEvalRunner.DEFAULT_CASES);

        System.out.println("== 通道消融（概念权重 0.6，精排关） ==");
        ablation(cases, "A. BM25 only",
                EnumSet.of(InMemoryHybridIndex.Channel.LEXICAL));
        ablation(cases, "B. BM25 + dense",
                EnumSet.of(InMemoryHybridIndex.Channel.LEXICAL, InMemoryHybridIndex.Channel.DENSE));
        ablation(cases, "C. BM25 + dense + concept",
                EnumSet.allOf(InMemoryHybridIndex.Channel.class));
        ablation(cases, "D. BM25 + concept",
                EnumSet.of(InMemoryHybridIndex.Channel.LEXICAL, InMemoryHybridIndex.Channel.CONCEPT));
    }

    private void ablation(List<RetrievalCase> cases, String tag,
                          EnumSet<InMemoryHybridIndex.Channel> ch) {
        var index = new io.github.tangbailin.novaorg.knowledge.ClasspathKnowledgeBase(
                new SemanticEmbeddingModel(1024, 0.6f),
                ClasspathKnowledgeBase.DEFAULT_LOCATION, RankFusion.DEFAULT_K, 20, 0.0, ch);
        report(index, cases, tag);
    }

    private void report(ClasspathKnowledgeBase kb, List<RetrievalCase> cases, String tag) {
        var m = new RetrievalEvalRunner(kb).run(cases);
        System.out.printf("  %-34s r@1=%5.1f%% r@3=%5.1f%% r@5=%5.1f%% hard@3=%5.1f%% mrr=%.3f%n",
                tag, m.recallAt1() * 100, m.recallAt3() * 100, m.recallAt5() * 100,
                m.hardRecallAt3() * 100, m.mrr());
    }
}
