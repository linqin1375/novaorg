package io.github.tangbailin.novaorg.config;

import io.github.tangbailin.novaorg.audit.AuditLog;
import io.github.tangbailin.novaorg.audit.InMemoryAuditLog;
import io.github.tangbailin.novaorg.knowledge.ClasspathKnowledgeBase;
import io.github.tangbailin.novaorg.knowledge.EmbeddingPort;
import io.github.tangbailin.novaorg.knowledge.HashingEmbeddingModel;
import io.github.tangbailin.novaorg.knowledge.KnowledgeBase;
import io.github.tangbailin.novaorg.llm.DemoLlmPort;
import io.github.tangbailin.novaorg.llm.LlmPort;
import io.github.tangbailin.novaorg.llm.OpenAiCompatLlmPort;
import io.github.tangbailin.novaorg.llm.RolePromptFactory;
import io.github.tangbailin.novaorg.metrics.CostModel;
import io.github.tangbailin.novaorg.org.RoleCatalog;
import io.github.tangbailin.novaorg.org.ToolCatalog;
import io.github.tangbailin.novaorg.plan.TaskPlanner;
import io.github.tangbailin.novaorg.policy.DefaultActionExecutor;
import io.github.tangbailin.novaorg.policy.RoleGuard;
import io.github.tangbailin.novaorg.runtime.AgentStepExecutor;
import io.github.tangbailin.novaorg.runtime.ReportComposer;
import io.github.tangbailin.novaorg.runtime.StepExecutor;
import io.github.tangbailin.novaorg.runtime.WorkflowEngine;
import io.github.tangbailin.novaorg.tools.BusinessSystems;

import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring only - every collaborator is a plain object that can be constructed in a unit test
 * without Spring, which is why the policy and workflow tests run in milliseconds.
 *
 * <p>The model backend is selected by one property: {@code novaorg.llm.mode=demo|live}.
 */
@Configuration
@EnableConfigurationProperties(NovaOrgProperties.class)
public class NovaOrgConfiguration {

    @Bean
    public RoleCatalog roleCatalog() {
        return new RoleCatalog();
    }

    @Bean
    public ToolCatalog toolCatalog() {
        return new ToolCatalog();
    }

    @Bean
    public AuditLog auditLog(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        return new io.github.tangbailin.novaorg.persistence.JdbcAuditLog(jdbc);
    }

    /**
     * 向量后端：默认离线概念增强模型；{@code novaorg.embedding.mode=api} 时换成
     * 任意 OpenAI 兼容的 /embeddings 端点。两条路径实现同一个 {@link EmbeddingPort}，
     * 检索代码一行都不用改，评测报告里两个实现的指标可以直接对照。
     */
    @Bean
    public EmbeddingPort embeddingPort(NovaOrgProperties properties) {
        NovaOrgProperties.Embedding cfg = properties.embedding();
        if (cfg != null && cfg.remote()) {
            if ((cfg.apiKey() == null || cfg.apiKey().isBlank()) && !loopback(cfg.baseUrl())) {
                throw new IllegalStateException(
                        "novaorg.embedding.mode=api 需要提供 API Key（环境变量 NOVAORG_EMBEDDING_API_KEY）");
            }
            return new io.github.tangbailin.novaorg.knowledge.OpenAiCompatEmbeddingModel(
                    cfg.baseUrl(), cfg.apiKey(), cfg.model(),
                    java.time.Duration.ofMillis(cfg.timeoutMs()),
                    cfg.dimensions() <= 0 ? 1024 : cfg.dimensions());
        }
        return new io.github.tangbailin.novaorg.knowledge.SemanticEmbeddingModel();
    }

    @Bean
    public KnowledgeBase knowledgeBase(EmbeddingPort embeddingPort, NovaOrgProperties properties) {
        NovaOrgProperties.Embedding cfg = properties.embedding();
        int rrfK = cfg == null || cfg.rrfK() <= 0 ? io.github.tangbailin.novaorg.knowledge.RankFusion.DEFAULT_K : cfg.rrfK();
        int depth = cfg == null || cfg.rerankDepth() <= 0 ? 20 : cfg.rerankDepth();
        // 0 = 关闭精排，走纯 RRF 排序。这是评测选出来的默认值，不是"忘了配"。
        double weight = cfg == null ? 0.0 : Math.max(0.0, Math.min(1.0, cfg.rerankWeight()));
        return new io.github.tangbailin.novaorg.knowledge.ClasspathKnowledgeBase(
                embeddingPort, io.github.tangbailin.novaorg.knowledge.ClasspathKnowledgeBase.DEFAULT_LOCATION,
                rrfK, depth, weight);
    }
    private static boolean loopback(String url) {
        String host=java.net.URI.create(url).getHost();
        return "localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }

    @Bean
    public BusinessSystems businessSystems(org.springframework.jdbc.core.JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager manager) {
        return new BusinessSystems(new io.github.tangbailin.novaorg.persistence.RefundLedger(jdbc,new org.springframework.transaction.support.TransactionTemplate(manager)));
    }

    @Bean
    public CostModel costModel(NovaOrgProperties properties) {
        return new CostModel(properties.cost().inputPerMillionUsd(),
                properties.cost().outputPerMillionUsd());
    }

    /**
     * The default model backend. Unchanged in behaviour and default: demo unless
     * {@code novaorg.llm.mode=live}.
     *
     * <p>Why {@link ConditionalOnMissingBean} instead of a plain property switch: the Spring AI
     * adapter lives in the profile-only source root {@code src/springai/java}, so it may or may not
     * be compiled in. A naive
     * {@code @ConditionalOnProperty(enabled=false)} here would leave the context with <b>zero</b>
     * {@code LlmPort} beans as soon as someone set {@code novaorg.llm.springai.enabled=true} without
     * building the profile - the application would fail to start with an unrelated-looking wiring
     * error. "Fall back only when nobody else provided one" is the correct semantics, and it keeps
     * exactly one {@code LlmPort} in the context in every combination:
     *
     * <pre>
     *   profile off, flag off (default)  -> this bean (demo or OpenAI-compatible)
     *   profile off, flag on             -> this bean; the flag is inert, not fatal
     *   profile on,  flag off            -> this bean (adapter declines)
     *   profile on,  flag on             -> the adapter
     * </pre>
     */
    @Bean
    @ConditionalOnMissingBean(LlmPort.class)
    public LlmPort llmPort(NovaOrgProperties properties,
                          org.springframework.beans.factory.ObjectProvider<io.github.tangbailin.novaorg.llm.LlmPortFactory> factories) {
        var factory = factories.getIfAvailable();
        if (factory != null) return factory.create();
        NovaOrgProperties.Llm llm = properties.llm();
        if (llm.live()) {
            if (llm.apiKey() == null || llm.apiKey().isBlank()) {
                throw new IllegalStateException(
                        "novaorg.llm.mode=live 需要提供 API Key（环境变量 NOVAORG_LLM_API_KEY）");
            }
            return new OpenAiCompatLlmPort(llm.baseUrl(), llm.apiKey(), llm.model(),
                    Duration.ofMillis(llm.timeoutMs()), llm.maxAttempts(), llm.strictTools(), llm.thinking());
        }
        return new DemoLlmPort();
    }

    @Bean
    public RolePromptFactory rolePromptFactory(ToolCatalog toolCatalog) {
        return new RolePromptFactory(toolCatalog);
    }

    @Bean
    public RoleGuard roleGuard(ToolCatalog toolCatalog, RoleCatalog roleCatalog, AuditLog auditLog) {
        return new RoleGuard(toolCatalog, roleCatalog, auditLog);
    }

    @Bean
    public DefaultActionExecutor actionExecutor(ToolCatalog toolCatalog, RoleCatalog roleCatalog,
                                                RoleGuard roleGuard, KnowledgeBase knowledgeBase,
                                                BusinessSystems business, AuditLog auditLog) {
        return new DefaultActionExecutor(toolCatalog, roleCatalog, roleGuard,
                knowledgeBase, business, auditLog);
    }

    @Bean
    public StepExecutor stepExecutor(LlmPort llmPort, RoleCatalog roleCatalog, ToolCatalog toolCatalog,
                                     RolePromptFactory promptFactory, KnowledgeBase knowledgeBase,
                                     DefaultActionExecutor actionExecutor, AuditLog auditLog) {
        return new AgentStepExecutor(llmPort, roleCatalog, toolCatalog, promptFactory,
                knowledgeBase, actionExecutor, auditLog);
    }

    @Bean
    public TaskPlanner taskPlanner(LlmPort llmPort, RoleCatalog roleCatalog, ToolCatalog toolCatalog,
                                   AuditLog auditLog, CostModel costModel, NovaOrgProperties properties) {
        return new TaskPlanner(llmPort, roleCatalog, toolCatalog, auditLog, costModel,
                properties.planner().maxSteps(), properties.planner().stepBudgetTokens());
    }

    @Bean
    public ReportComposer reportComposer(RoleCatalog roleCatalog) {
        return new ReportComposer(roleCatalog);
    }

    @Bean
    public WorkflowEngine workflowEngine(TaskPlanner planner, StepExecutor stepExecutor,
                                         RoleCatalog roleCatalog, ToolCatalog toolCatalog,
                                         AuditLog auditLog, CostModel costModel,
                                         ReportComposer reportComposer,
                                         DefaultActionExecutor actionExecutor,
                                         org.springframework.jdbc.core.JdbcTemplate jdbc,
                                         org.springframework.transaction.PlatformTransactionManager manager) {
        return new WorkflowEngine(planner, stepExecutor, roleCatalog, toolCatalog,
                auditLog, costModel, reportComposer, actionExecutor).withStore(new io.github.tangbailin.novaorg.persistence.JdbcTaskStore(jdbc,new org.springframework.transaction.support.TransactionTemplate(manager)));
    }
}
