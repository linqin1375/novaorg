package io.github.tangbailin.novaorg.llm;

import io.github.tangbailin.novaorg.config.NovaOrgProperties;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Third {@link LlmPort} implementation: Spring AI's {@code ChatClient} behind the same port.
 *
 * <p>This is the code side of the promise made in {@code docs/ARCHITECTURE.md} section 2.6 - the
 * runtime talks to models through {@code LlmPort} only, so a provider SDK can be swapped in
 * <em>without touching the orchestration, permission, audit or SLA layers</em>.
 *
 * <h2>Where this file lives, and why that matters</h2>
 * This class is <b>not</b> under {@code src/main/java}. It sits in the profile-only source root
 * {@code src/springai/java}, which Maven adds only when the {@code springai} profile is active.
 *
 * <p>The obvious alternative - a normal dependency marked {@code <optional>true</optional>} - was
 * tried first and <b>reverted</b>, because {@code optional} only stops <em>transitivity</em>: the
 * artifact still lands on this module's own runtime and test classpath. That was enough to make
 * Spring AI's OpenAI auto-configuration active in a plain {@code mvn spring-boot:run}, and it then
 * aborted startup with {@code OpenAI API key must be set} (from
 * {@code OpenAiAudioSpeechAutoConfiguration} and friends). It broke the project's headline
 * property: clone it and it runs with zero credentials.
 *
 * <p>So the guard is the build itself. In a default build Spring AI is not on the classpath, this
 * class does not exist, the OpenAI auto-configurations cannot activate, and no key is ever
 * demanded. {@code @ConditionalOnClass} is kept as a second line of defence for the case where
 * someone puts the starter on the classpath without the profile.
 *
 * <h2>Enabling it</h2>
 * <pre>
 *   ./mvnw -Pspringai spring-boot:run \
 *     -Dspring-boot.run.arguments="--novaorg.llm.springai.enabled=true --spring.ai.openai.api-key=sk-xxx"
 * </pre>
 * The switch is {@code novaorg.llm.springai.enabled} (default {@code false}) rather than Spring
 * AI's own {@code spring.ai.chat.client.enabled}, so that turning the adapter on is a separate,
 * explicit act from merely having the profile compiled in. Even with the profile active, an
 * unconfigured run still boots the demo backend.
 *
 * <p>The endpoint and model come from the standard Spring AI properties
 * ({@code spring.ai.openai.base-url}, {@code spring.ai.openai.chat.options.model}, ...);
 * {@link #id()} reports the project's own {@code novaorg.llm.model} so the run label reads the
 * same whichever backend is behind the port.
 *
 * <p>Mapping is deliberately total: this adapter is the only place in the codebase that knows
 * Spring AI exists.
 */
public class SpringAiLlmAdapter implements LlmPort {

    private final ChatClient chatClient;
    private final String model;

    public SpringAiLlmAdapter(ChatClient chatClient, String model) {
        this.chatClient = chatClient;
        this.model = (model == null || model.isBlank()) ? "unspecified" : model;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        long start = System.nanoTime();

        String system = request.systemPrompt() == null ? "" : request.systemPrompt();
        String user = request.userPrompt() == null ? "" : request.userPrompt();

        ChatOptions options = ChatOptions.builder()
                .maxTokens(request.maxOutputTokens())
                .temperature(request.temperature())
                .stopSequences(request.stopHints() == null || request.stopHints().isEmpty()
                        ? null : request.stopHints())
                .build();

        ChatClient.ChatClientRequestSpec spec = chatClient.prompt().options(options);
        // systemPrompt -> system message, userPrompt -> user message
        if (!system.isBlank()) {
            spec = spec.system(system);
        }
        ChatResponse response = spec.user(user).call().chatResponse();

        long latencyMs = Math.max(1L, (System.nanoTime() - start) / 1_000_000L);

        String text = extractText(response);
        Usage usage = extractUsage(response);
        int inputTokens = resolveTokens(
                usage == null ? null : usage.getPromptTokens(),
                () -> DemoLlmPort.estimateTokens(system) + DemoLlmPort.estimateTokens(user));
        int outputTokens = resolveTokens(
                usage == null ? null : usage.getCompletionTokens(),
                () -> DemoLlmPort.estimateTokens(text));

        return LlmResponse.of(text, inputTokens, outputTokens, latencyMs, id());
    }

    private static String extractText(ChatResponse response) {
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    private static Usage extractUsage(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return null;
        }
        try {
            return response.getMetadata().getUsage();
        } catch (RuntimeException e) {
            // a provider that reports no usage block must degrade, not fail the run
            return null;
        }
    }

    /**
     * Provider-reported usage wins; when the endpoint omits it (or reports {@code null} for a
     * field) fall back to the same deterministic estimator the demo backend uses, so the SLA and
     * cost layers always receive a number.
     */
    private static int resolveTokens(Integer reported,
                                     java.util.function.IntSupplier fallback) {
        if (reported != null && reported > 0) {
            return reported;
        }
        return fallback.getAsInt();
    }

    @Override
    public String id() {
        return "spring-ai:" + model;
    }

    @Override
    public boolean isLive() {
        return true;
    }

    /** Contributes an optional factory; core wiring owns the single LlmPort bean. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration")
    @ConditionalOnProperty(prefix = "novaorg.llm.springai", name = "enabled", havingValue = "true")
    public static class AutoConfiguration {

        @Bean
        public LlmPortFactory springAiPortFactory(NovaOrgProperties properties, ChatClient.Builder chatClientBuilder) {
            // the model label comes from the project's own property, so `id()` reads the same
            // way whichever backend is behind LlmPort
            return () -> new SpringAiLlmAdapter(chatClientBuilder.build(), properties.llm().model());
        }
    }
}
