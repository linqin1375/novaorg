package io.github.tangbailin.novaorg.plan;
import io.github.tangbailin.novaorg.llm.*;
import io.github.tangbailin.novaorg.org.*;
import io.github.tangbailin.novaorg.audit.*;
import io.github.tangbailin.novaorg.metrics.CostModel;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class PlannerCapabilityTest {
    private TaskPlanner planner(String intent,String roles) {
        var llm=new LlmPort() {
            public String id() { return "live-stub"; }
            public boolean isLive() { return true; }
            public LlmResponse complete(LlmRequest request) {
                return LlmResponse.of("{\"intent\":\""+intent+"\",\"orderId\":\"A101\",\"roles\":\""+roles+"\",\"complexity\":\"SIMPLE\",\"rationale\":\"test\"}",50,40,1,id());
            }
        };
        return new TaskPlanner(llm,new RoleCatalog(),new ToolCatalog(),new InMemoryAuditLog(),CostModel.defaultModel(),4,8000);
    }
    @Test void calculationCannotBeStrandedInCustomerServiceWithoutPolicyOrCalculator() {
        var plan=planner("CALCULATION",RoleCatalog.CUSTOMER_SERVICE).plan("t","试算订单 A101 的赔付");
        assertThat(plan.steps()).hasSize(1);
        assertThat(plan.steps().get(0).roleId()).isEqualTo(RoleCatalog.COMPENSATION);
        assertThat(plan.steps().get(0).allowedTools()).contains(ToolCatalog.COMPENSATION_CALC);
        assertThat(plan.requestIntent()).isEqualTo("CALCULATION");
    }
    @Test void policyQuestionDoesNotRequireAnExtraComplianceWorkflow() {
        var plan=planner("POLICY_QA",RoleCatalog.CUSTOMER_SERVICE+","+RoleCatalog.COMPLIANCE).plan("t","知识问答：银行卡退款到账时效，不执行退款");
        assertThat(plan.steps()).hasSize(1);assertThat(plan.steps().get(0).roleId()).isEqualTo(RoleCatalog.CUSTOMER_SERVICE);
    }
    @Test void invalidLiveIntentIsNotSilentlyConvertedToAnOfflineFallback() {
        assertThatThrownBy(()->planner("INVENTED",RoleCatalog.CUSTOMER_SERVICE).plan("t","test")).isInstanceOf(IllegalArgumentException.class);
    }
}
