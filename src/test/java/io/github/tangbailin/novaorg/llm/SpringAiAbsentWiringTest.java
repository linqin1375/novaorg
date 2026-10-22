package io.github.tangbailin.novaorg.llm;

import io.github.tangbailin.novaorg.config.NovaOrgConfiguration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.util.ClassUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The zero-credential guarantee, asserted against a real container and a real classpath.
 *
 * <p>{@code docs/ARCHITECTURE.md} section 2.6 promises that Spring AI can sit behind
 * {@link LlmPort}. The other half of that promise - the one that is easy to break, and which was
 * in fact broken once during development - is that <b>nothing changes when Spring AI is absent</b>:
 * no {@code NoClassDefFoundError}, no API key demanded, and the {@code LlmPort} the runtime
 * receives is still the offline one.
 *
 * <p>This test is the tripwire for that. It is not hypothetical: an earlier attempt declared Spring
 * AI as an {@code <optional>true</optional>} dependency and relied on {@code @ConditionalOnClass}
 * alone. Optional dependencies still reach this module's own test classpath, so Spring AI's OpenAI
 * auto-configuration became active in a plain {@code mvn spring-boot:run} and aborted startup with
 * {@code OpenAI API key must be set}. The first assertion below is exactly the check that catches
 * that regression.
 *
 * <p>It is also why the adapter lives in {@code src/springai/java} and this test carries no
 * profile-based skip: in the default build it runs and must pass, and the adapter tests live in
 * their own source root so they simply are not compiled here.
 */
class SpringAiAbsentWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SilentDemoContext.class)
            .withBean(org.springframework.jdbc.core.JdbcTemplate.class, ()->org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class))
            .withBean(org.springframework.transaction.PlatformTransactionManager.class, ()->org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class))
            // Same values as src/main/resources/application.yml. NovaOrgProperties is a record tree,
            // so every group has to be present or the nested records bind to null - this runner
            // loads only the wiring configuration, not the application's own property sources.
            .withPropertyValues(
                    "novaorg.llm.mode=demo",
                    "novaorg.llm.base-url=https://api.deepseek.com/v1",
                    "novaorg.llm.api-key=",
                    "novaorg.llm.model=deepseek-chat",
                    "novaorg.llm.timeout-ms=45000",
                    "novaorg.llm.max-attempts=2",
                    "novaorg.planner.max-steps=4",
                    "novaorg.planner.step-budget-tokens=1500",
                    "novaorg.cost.input-per-million-usd=0.30",
                    "novaorg.cost.output-per-million-usd=1.20",
                    "novaorg.demo.seed-on-startup=false",
                    "novaorg.demo.seed-gate-task=false",
                    "novaorg.demo.seed-escalation-task=false",
                    "novaorg.approval.require-separate-approver=true",
                    "novaorg.approval.default-approver=delivery_manager");

    /**
     * The tripwire. If Spring AI ever creeps back onto the default classpath, its OpenAI
     * auto-configuration becomes active and demands a key - which is exactly how the
     * zero-credential startup was broken once already.
     */
    @Test
    void springAiIsNotOnTheDefaultClasspath() {
        boolean present = ClassUtils.isPresent("org.springframework.ai.chat.client.ChatClient",
                getClass().getClassLoader());

        if (springAiProfileActive()) {
            // Under -Pspringai the adapter IS built in on purpose. The claim being verified in that
            // run is the other half: its presence alone still must not demand credentials, which the
            // port-wiring tests below cover.
            assertThat(present).as("-Pspringai 下 Spring AI 应在类路径上").isTrue();
            return;
        }

        assertThat(present)
                .as("默认构建下 Spring AI 不应出现在类路径上——"
                        + "否则它的 OpenAI 自动配置会要求 API Key，破坏零凭证启动")
                .isFalse();
        assertThat(ClassUtils.isPresent(
                "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration",
                getClass().getClassLoader()))
                .as("默认构建下 Spring AI 的自动配置不应存在")
                .isFalse();
    }

    /** Set by the springai profile's surefire configuration. */
    private boolean springAiProfileActive() {
        return "true".equalsIgnoreCase(System.getProperty("novaorg.springai.profile.active"));
    }

    /**
     * The container must never fall back to a "helpful" default backend: with the demo backend
     * selected, a {@link DemoLlmPort} is what the orchestrator injects.
     */
    @Test
    void llmPortIsTheOfflineBackendByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            LlmPort port = context.getBean(LlmPort.class);

            assertThat(port).isInstanceOf(DemoLlmPort.class);
            assertThat(port.isLive()).isFalse();
            assertThat(port.id()).isEqualTo("demo-deterministic-rules");
        });
    }

    /**
     * The live (OpenAI-compatible) backend must stay reachable without Spring AI, and a missing key
     * must still fail loudly rather than silently degrading - the zero-credential guarantee is about
     * demo mode, not about swallowing a misconfiguration.
     */
    @Test
    void llmPortCanStillBeTheOpenAiCompatibleBackendWithAKey() {
        runner.withPropertyValues("novaorg.llm.mode=live", "novaorg.llm.api-key=test-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(LlmPort.class))
                            .isInstanceOf(OpenAiCompatLlmPort.class)
                            .satisfies(port -> assertThat(port.isLive()).isTrue());
                });
    }

    @Test
    void liveModeWithoutAKeyStillFailsLoudly() {
        runner.withPropertyValues("novaorg.llm.mode=live").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseMessage("novaorg.llm.mode=live 需要提供 API Key（环境变量 NOVAORG_LLM_API_KEY）");
        });
    }

    /**
     * Asking for the Spring AI backend without having built it in must be a no-op, not a crash and
     * not a demand for credentials. The switch is inert when the profile was never compiled, which
     * is what lets this property sit in a config file safely.
     */
    @Test
    void askingForSpringAiWithoutTheProfileStillBootsOffline() {
        runner.withPropertyValues("novaorg.llm.springai.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(LlmPort.class)).isInstanceOf(DemoLlmPort.class);
        });
    }

    /**
     * The real {@code LlmPort} wiring, minus the pieces that need a database or a network. In the
     * default build this is the whole story; under {@code -Pspringai} the adapter's own
     * configuration joins in and is covered by {@code SpringAiLlmAdapterProfileTest}.
     */
    @Configuration(proxyBeanMethods = false)
    @Import(NovaOrgConfiguration.class)
    static class SilentDemoContext {
    }
}
