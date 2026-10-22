package io.github.tangbailin.novaorg.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All tunables in one place. The interesting ones are the <b>cost model</b>, the <b>SLA</b>
 * numbers and the <b>governance switches</b>: they are configuration, not magic constants, so a
 * reviewer can see exactly what "budget", "budget breach" and "who may approve" mean here.
 */
@ConfigurationProperties(prefix = "novaorg")
public record NovaOrgProperties(
        Llm llm,
        Embedding embedding,
        Planner planner,
        Cost cost,
        Demo demo,
        Approval approval
) {
    public record Llm(String mode, String baseUrl, String apiKey, String model,
                      long timeoutMs, int maxAttempts, boolean strictTools, boolean thinking) {
        @org.springframework.boot.context.properties.bind.ConstructorBinding
        public Llm {}
        public Llm(String mode,String url,String key,String model,long timeout,int attempts) {
            this(mode,url,key,model,timeout,attempts,false,false);
        }
        public boolean live() {
            return "live".equalsIgnoreCase(mode);
        }
    }

    /**
     * 向量后端。{@code local}（默认）= 离线的概念增强模型，零配置、可复现；
     * {@code api} = 任何 OpenAI 兼容的 {@code /embeddings} 端点。
     *
     * <p>{@code rerank-depth} / {@code rrf-k} 是两阶段检索的可调参数，放在配置里而不是
     * 常量里，因为它们在评测报告中被对照过。
     */
    public record Embedding(String mode, String baseUrl, String apiKey, String model,
                            long timeoutMs, int dimensions, int rrfK, int rerankDepth,
                            double rerankWeight) {
        public boolean remote() {
            return "api".equalsIgnoreCase(mode);
        }
    }

    public record Planner(int maxSteps, int stepBudgetTokens) {
    }

    public record Cost(double inputPerMillionUsd, double outputPerMillionUsd) {
    }

    /**
     * Seeds a couple of demonstrative tasks at startup so the console and the metrics endpoint are
     * not empty on first open. Turn it off for anything that looks like production.
     */
    public record Demo(boolean seedOnStartup, boolean seedGateTask, boolean seedEscalationTask) {
    }

    /**
     * Four-eyes principle for the approval gate: the person who releases a high-risk action must
     * not be the person who asked for it.
     */
    public record Approval(boolean requireSeparateApprover, String defaultApprover) {
    }
}
