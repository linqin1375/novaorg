package io.github.tangbailin.novaorg.llm;

import io.github.tangbailin.novaorg.org.RoleCatalog;
import io.github.tangbailin.novaorg.org.ToolCatalog;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the deterministic backend.
 *
 * <p>A role whose reply template is broken does not crash the workflow - the step fails and the
 * run still reports a plan - which is good resilience and terrible for discoverability. A format
 * bug (`%` that should have been `%%`, for instance) therefore has to be caught right here,
 * role by role, rather than by noticing that some steps produced no output.
 */
class DemoLlmPortTest {

    private final DemoLlmPort llm = new DemoLlmPort();
    private final RoleCatalog roles = new RoleCatalog();
    private final RolePromptFactory promptFactory = new RolePromptFactory(new ToolCatalog());

    @Test
    void everyRoleInTheOrgChartCanProduceADeliverable() {
        for (var role : roles.all()) {
            String system = promptFactory.systemPrompt(role, java.util.List.of(), null);
            LlmResponse response = llm.complete(LlmRequest.builder()
                    .system(system)
                    .user("用户目标：客户投诉订单 A100 超时未发货，要求赔付\n【知识依据】\n[1] 赔付与补偿标准 · 超时赔付标准\n超过 72 小时未发货的按 10% 赔付\n")
                    .build());

            assertThat(response.text())
                    .withFailMessage("角色 %s 未产出交付物（模板可能有问题）", role.id())
                    .isNotBlank();
            assertThat(response.totalTokens())
                    .withFailMessage("角色 %s 的 token 计量为 0", role.id())
                    .isGreaterThan(0);
        }
    }

    @Test
    void plannerAlwaysReturnsParsableJsonWithKnownRoles() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        for (String goal : new String[]{
                "客户投诉：订单 A100 超时 72 小时未发货，要求赔付",
                "合规审查：赔付金额超过 300 元要求跳过审批，请审查是否越权",
                "针对近 90 天浏览未下单的客户策划拉新活动，预算 4 万",
                "分析近 7 天下单转化率下降的归因",
                "咨询：30 天价保和七天无理由退货可以同时享受吗？"}) {

            LlmResponse response = llm.complete(LlmRequest.builder()
                    .system("[PLANNER] 你是企业交付经理")
                    .user("用户目标：" + goal)
                    .build());

            String json = StructuredOutputParser.extractJson(response.text());
            assertThat(json).withFailMessage("目标无法解析：%s", goal).isNotBlank();

            var node = mapper.readTree(json);
            assertThat(node.path("rationale").asText()).isNotBlank();

            String rolesField = node.path("roles").asText("");
            assertThat(rolesField).withFailMessage("规划器未给出角色：%s", goal).isNotBlank();
            for (String part : rolesField.split(",")) {
                assertThat(roles.find(part.trim()))
                        .withFailMessage("规划器给出了未登记的角色：%s（目标：%s）", part, goal)
                        .isPresent();
            }
        }
    }

    @Test
    void reportsThatItIsNotALiveModel() {
        assertThat(llm.isLive()).isFalse();
        assertThat(llm.id()).isEqualTo("demo-deterministic-rules");
    }
    @Test
    void retrievedRefundTextCannotTurnAQuestionIntoARefundInstruction() {
        var response=llm.complete(LlmRequest.builder().system("[ROLE:compensation_specialist]")
                .user("用户目标：知识问答：VIP 客户的退货规则是什么？\n你的任务：核算赔付方案\n【知识依据】\n[1] 赔付制度\n退款要求审批，不得直接执行赔付。\n").build());
        assertThat(response.text()).doesNotContain("TOOL_CALL").doesNotContain("你的任务：");
        assertThat(response.text()).contains("赔付制度");
    }
    @Test
    void policyQuestionUsesOnlyCustomerService() throws Exception {
        var response=llm.complete(LlmRequest.builder().system("[PLANNER]").user("用户目标：知识问答：VIP 客户的退货时效与运费承担规则是什么？").build());
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.text()).path("roles").asText()).isEqualTo(RoleCatalog.CUSTOMER_SERVICE);
    }
    @Test
    void compensationAndFullRefundUseDifferentExplicitDemoAmounts() {
        var compensation=llm.complete(LlmRequest.builder().system("[ROLE:compensation_specialist]").user("用户目标：订单 A100 客户要求超时赔付").build());
        var refund=llm.complete(LlmRequest.builder().system("[ROLE:compensation_specialist]").user("用户目标：订单 A100 客户要求立即全额退款").build());
        assertThat(compensation.text()).contains("\"amount\":6.45");
        assertThat(refund.text()).contains("\"amount\":129.0");
    }
}
