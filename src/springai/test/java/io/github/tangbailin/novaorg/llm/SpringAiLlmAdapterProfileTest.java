package io.github.tangbailin.novaorg.llm;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The adapter's own tests. This file lives in {@code src/springai/test/java} and therefore only
 * exists under {@code ./mvnw -Pspringai test} - the only build where Spring AI is on the classpath.
 *
 * <p>It exercises the adapter end to end through a <b>stub {@link ChatModel}</b> - no API key, no
 * network, no recorded fixtures - so the assertions are about the mapping this project actually
 * owns:
 * <ul>
 *   <li>{@code systemPrompt} / {@code userPrompt} reach Spring AI as a system and a user message;</li>
 *   <li>the {@code LlmRequest} budget reaches Spring AI as {@code ChatOptions};</li>
 *   <li>the provider's reported usage becomes {@code inputTokens} / {@code outputTokens};</li>
 *   <li>a provider that reports no usage degrades to {@code DemoLlmPort.estimateTokens} instead of
 *       returning zero (the SLA and cost layers depend on those numbers);</li>
 *   <li>{@code id()} and {@code isLive()} identify the backend honestly.</li>
 * </ul>
 *
 * <p>What it deliberately does <b>not</b> do is call a real endpoint - that needs credentials and
 * belongs in a manual run, not in CI.
 */
class SpringAiLlmAdapterProfileTest {

    private static final String SYSTEM = "你是赔付专员，只能用已授权的工具。[ROLE:compensation_specialist]";
    private static final String USER = "用户目标：订单 A100 超时未发货，要求赔付";
    private static final String REPLY = "赔付方案：按超时档位赔付 129.0 元。";

    @Test
    void mapsPromptsToMessagesAndUsageToTokens() {
        StubChatModel stub = new StubChatModel(REPLY, new DefaultUsage(1234, 56, 1290));
        LlmPort port = new SpringAiLlmAdapter(ChatClient.builder(stub).build(), "deepseek-chat");

        LlmResponse response = port.complete(LlmRequest.builder()
                .system(SYSTEM)
                .user(USER)
                .maxOutputTokens(256)
                .temperature(0.3)
                .build());

        // system -> system message, user -> user message
        List<Message> sent = stub.captured.getInstructions();
        assertThat(sent).hasSizeGreaterThanOrEqualTo(2);
        assertThat(sent.stream().filter(m -> m.getMessageType() == MessageType.SYSTEM).toList())
                .singleElement()
                .satisfies(m -> assertThat(m.getText()).isEqualTo(SYSTEM));
        assertThat(sent.stream().filter(m -> m.getMessageType() == MessageType.USER).toList())
                .singleElement()
                .satisfies(m -> assertThat(m.getText()).isEqualTo(USER));

        // the LlmRequest budget is forwarded to Spring AI instead of being dropped
        assertThat(stub.captured.getOptions()).isNotNull();
        assertThat(stub.captured.getOptions().getMaxTokens()).isEqualTo(256);
        assertThat(stub.captured.getOptions().getTemperature()).isEqualTo(0.3);

        // provider-reported usage wins
        assertThat(response.text()).isEqualTo(REPLY);
        assertThat(response.inputTokens()).isEqualTo(1234);
        assertThat(response.outputTokens()).isEqualTo(56);
        assertThat(response.totalTokens()).isEqualTo(1290);
        assertThat(response.latencyMs()).isGreaterThanOrEqualTo(1L);
        assertThat(response.provider()).isEqualTo("spring-ai:deepseek-chat");
    }

    @Test
    void fallsBackToTheLocalEstimatorWhenTheProviderReportsNoUsage() {
        StubChatModel stub = new StubChatModel(REPLY, null);
        LlmPort port = new SpringAiLlmAdapter(ChatClient.builder(stub).build(), "deepseek-chat");

        LlmResponse response = port.complete(LlmRequest.of(SYSTEM, USER));

        int expectedIn = DemoLlmPort.estimateTokens(SYSTEM) + DemoLlmPort.estimateTokens(USER);
        int expectedOut = DemoLlmPort.estimateTokens(REPLY);

        assertThat(response.inputTokens()).isEqualTo(expectedIn);
        assertThat(response.outputTokens()).isEqualTo(expectedOut);
        assertThat(response.inputTokens()).isGreaterThan(0);
        assertThat(response.outputTokens()).isGreaterThan(0);
    }

    @Test
    void reportsTheBackendHonestly() {
        StubChatModel stub = new StubChatModel(REPLY, null);
        LlmPort port = new SpringAiLlmAdapter(ChatClient.builder(stub).build(), "qwen-plus");

        assertThat(port.id()).isEqualTo("spring-ai:qwen-plus");
        assertThat(port.isLive()).isTrue();
    }

    /** A {@link ChatModel} that records the {@link Prompt} and replays a canned answer. */
    private static final class StubChatModel implements ChatModel {

        private final String reply;
        private final DefaultUsage usage;
        private Prompt captured;

        private StubChatModel(String reply, DefaultUsage usage) {
            this.reply = reply;
            this.usage = usage;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            this.captured = prompt;
            ChatResponseMetadata metadata = usage == null
                    ? ChatResponseMetadata.builder().build()
                    : ChatResponseMetadata.builder().usage(usage).build();
            return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))), metadata);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }
    }
}
