package io.github.tangbailin.novaorg.llm;
import io.github.tangbailin.novaorg.support.StubProvider;
import io.github.tangbailin.novaorg.audit.*;
import io.github.tangbailin.novaorg.domain.*;
import io.github.tangbailin.novaorg.knowledge.*;
import io.github.tangbailin.novaorg.org.*;
import io.github.tangbailin.novaorg.policy.*;
import io.github.tangbailin.novaorg.runtime.*;
import io.github.tangbailin.novaorg.tools.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class NativeConversationTest {
    private static String reply(String message) { return "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":"+message+"}],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":30}}"; }
    private StepResult execute(LlmPort llm) {
        var roles=new RoleCatalog();var tools=new ToolCatalog();var audit=new InMemoryAuditLog();
        var kb=new ClasspathKnowledgeBase(new SemanticEmbeddingModel());
        var actions=new DefaultActionExecutor(tools,roles,new RoleGuard(tools,roles,audit),kb,new BusinessSystems(),audit);
        var executor=new AgentStepExecutor(llm,roles,tools,new RolePromptFactory(tools),kb,actions,audit);
        var step=new TaskStep("s",RoleCatalog.CUSTOMER_SERVICE,"查询订单",List.of(ToolCatalog.ORDER_DETAIL),List.of("policy"),8000);
        return executor.execute(new TaskPlan("native","查询订单 A101",Complexity.SIMPLE,List.of(step),"测试"),step,new StepExecutor.ExecutionContext(false,"native"));
    }
    @Test void nativeToolIdArgumentsAndResultSurviveTheNextHttpRequest() throws Exception {
        try(var server=new StubProvider(reply("{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call-7\",\"type\":\"function\",\"function\":{\"name\":\"order__detail\",\"arguments\":\"{\\\"orderId\\\":\\\"A101\\\"}\"}}]}"),
                reply("{\"role\":\"assistant\",\"content\":\"订单 A101 实付259元，尚未退款\"}"))) {
            var result=execute(new OpenAiCompatLlmPort(server.url(),"fake","stub",Duration.ofSeconds(2),1));
            assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
            assertThat(result.toolCalls()).hasSize(1);
            assertThat(result.toolCalls().get(0).origin()).isEqualTo(CallOrigin.MODEL);
            var messages=server.requests.get(1).path("messages");
            assertThat(messages.get(2).path("tool_calls").get(0).path("id").asText()).isEqualTo("call-7");
            assertThat(messages.get(3).path("role").asText()).isEqualTo("tool");
            assertThat(messages.get(3).path("tool_call_id").asText()).isEqualTo("call-7");
            assertThat(messages.get(3).path("content").asText()).contains("A101").contains("25900");
            assertThat(result.trace()).hasSize(2);
            assertThat(result.trace().get(0).usageEstimated()).isFalse();
        }
    }
    @Test void textThatMentionsToolCallCannotExecuteInLiveMode() throws Exception {
        try(var server=new StubProvider(reply("{\"content\":\"TOOL_CALL {\\\"name\\\":\\\"order.detail\\\",\\\"arguments\\\":{\\\"orderId\\\":\\\"A100\\\"}}\"}"))) {
            var result=execute(new OpenAiCompatLlmPort(server.url(),"fake","stub",Duration.ofSeconds(2),1));
            assertThat(result.toolCalls()).isEmpty();
            assertThat(result.stopReason()).isEqualTo(StopReason.INVALID_OUTPUT);
        }
    }
    @Test void malformedNativeArgumentsFailInsteadOfBecomingEmptyArguments() throws Exception {
        try(var server=new StubProvider(reply("{\"tool_calls\":[{\"id\":\"bad\",\"function\":{\"name\":\"order__detail\",\"arguments\":\"not json\"}}]}"))) {
            var llm=new OpenAiCompatLlmPort(server.url(),"fake","stub",Duration.ofSeconds(2),1);
            assertThatThrownBy(()->llm.complete(LlmRequest.of("system","user"))).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void strictToolsAreExplicitAndOptionalArgumentsAreNullable() throws Exception {
        try(var server=new StubProvider(reply("{\"content\":\"ok\"}"))) {
            var llm=new OpenAiCompatLlmPort(server.url(),"fake","stub",Duration.ofSeconds(2),1,true,false);
            llm.complete(LlmRequest.builder().user("query").tools(ToolSchemas.of(List.of(new ToolCatalog().find(ToolCatalog.KNOWLEDGE_SEARCH).orElseThrow()))).build());
            var fn=server.requests.get(0).path("tools").get(0).path("function");
            assertThat(fn.path("strict").asBoolean()).isTrue();
            assertThat(fn.path("name").asText()).isEqualTo("knowledge__search");
            assertThat(fn.path("parameters").path("required").size()).isEqualTo(3);
            assertThat(fn.path("parameters").path("properties").path("namespace").has("anyOf")).isTrue();
        }
    }
}
