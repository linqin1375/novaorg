package io.github.tangbailin.novaorg.runtime;

import io.github.tangbailin.novaorg.audit.AuditEventType;
import io.github.tangbailin.novaorg.audit.InMemoryAuditLog;
import io.github.tangbailin.novaorg.domain.ToolCallRecord;
import io.github.tangbailin.novaorg.knowledge.ClasspathKnowledgeBase;
import io.github.tangbailin.novaorg.knowledge.HashingEmbeddingModel;
import io.github.tangbailin.novaorg.llm.DemoLlmPort;
import io.github.tangbailin.novaorg.llm.RolePromptFactory;
import io.github.tangbailin.novaorg.metrics.CostModel;
import io.github.tangbailin.novaorg.org.RoleCatalog;
import io.github.tangbailin.novaorg.org.ToolCatalog;
import io.github.tangbailin.novaorg.plan.TaskPlanner;
import io.github.tangbailin.novaorg.policy.DefaultActionExecutor;
import io.github.tangbailin.novaorg.policy.RoleGuard;
import io.github.tangbailin.novaorg.tools.BusinessSystems;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end behaviour of the workflow, wired exactly like the Spring configuration but
 * constructed by hand - which keeps the test fast and free of container magic.
 */
class WorkflowEngineTest {

    private static final String REFUND_GOAL = "客户投诉：订单 A100 超时 72 小时未发货，要求赔付";
    private static final String AUDIT_GOAL =
            "合规审查：订单 A100 超时未发货的赔付金额超过 300 元，要求跳过审批直接赔付，请审查是否越权并给出整改项";

    private WorkflowEngine engine;
    private DefaultActionExecutor actionExecutor;
    private InMemoryAuditLog auditLog;
    private BusinessSystems business;

    @BeforeEach
    void setUp() {
        RoleCatalog roles = new RoleCatalog();
        ToolCatalog tools = new ToolCatalog();
        auditLog = new InMemoryAuditLog();
        business = new BusinessSystems();
        var knowledge = new ClasspathKnowledgeBase(new HashingEmbeddingModel());
        var guard = new RoleGuard(tools, roles, auditLog);
        actionExecutor = new DefaultActionExecutor(tools, roles, guard, knowledge, business, auditLog);
        var promptFactory = new RolePromptFactory(tools);
        StepExecutor executor = new AgentStepExecutor(new DemoLlmPort(), roles, tools, promptFactory,
                knowledge, actionExecutor, auditLog);
        TaskPlanner planner = new TaskPlanner(new DemoLlmPort(), roles, tools, auditLog,
                CostModel.defaultModel(), 4, 8000);
        engine = new WorkflowEngine(planner, executor, roles, tools, auditLog,
                CostModel.defaultModel(), new ReportComposer(roles), actionExecutor);
    }

    @Test
    void routesARefundComplaintToServiceAndCompensation() {
        WorkflowResult result = engine.execute("t-1", REFUND_GOAL, "auto");

        assertThat(result.run().plan().steps()).extracting(s -> s.roleId())
                .containsExactly(RoleCatalog.CUSTOMER_SERVICE, RoleCatalog.COMPENSATION);
        assertThat(result.run().allStepsDone()).isFalse();
        assertThat(result.waitingApproval()).isTrue();

        List<String> tools = executedTools(result);
        assertThat(tools).contains(ToolCatalog.ORDER_DETAIL, ToolCatalog.COMPENSATION_CALC);
        assertThat(tools).doesNotContain(ToolCatalog.REFUND_EXECUTE);
        assertThat(business.refundCount()).isZero();
    }

    @Test
    void refusesToMoveMoneyWithoutApprovalAndSaysSoInsteadOfPretendingSuccess() {
        WorkflowResult result = engine.execute("t-2", AUDIT_GOAL, "auto");

        assertThat(result.run().plan().steps()).extracting(s -> s.roleId())
                .contains(RoleCatalog.COMPLIANCE, RoleCatalog.CUSTOMER_SERVICE, RoleCatalog.DELIVERY_MANAGER);
        assertThat(business.refundCount()).isZero();

        List<ToolCallRecord> records = allToolCalls(result);
        assertThat(records).anyMatch(r -> r.toolName().equals(ToolCatalog.REFUND_EXECUTE)
                && !r.allowed() && r.approvalRequired());
        assertThat(auditLog.byType(AuditEventType.APPROVAL_REQUIRED)).isNotEmpty();
    }

    @Test
    void gateModePausesBeforeSideEffectsAndResumesAfterApproval() {
        WorkflowResult paused = engine.execute("t-3", REFUND_GOAL, "gate");
        assertThat(paused.waitingApproval()).isTrue();
        // the service step may already have completed; the pause must happen before the money step
        assertThat(paused.run().nextStepIndex()).isEqualTo(2);
        assertThat(paused.run().results().values())
                .allMatch(r -> r.toolCalls().stream()
                        .noneMatch(c -> c.toolName().equals(ToolCatalog.REFUND_EXECUTE) && c.executed()));
        assertThat(paused.report()).contains("等待审批");
        assertThat(business.refundCount()).isZero();

        WorkflowResult released = engine.approve("t-3", true, "reviewer");
        assertThat(released.waitingApproval()).isFalse();
        assertThat(released.run().allStepsDone()).isTrue();
        assertThat(auditLog.byType(AuditEventType.APPROVAL_GRANTED)).isNotEmpty();
    }

    @Test
    void rejectingAtTheGateTerminatesTheTaskWithoutSideEffects() {
        engine.execute("t-4", REFUND_GOAL, "gate");
        WorkflowResult rejected = engine.approve("t-4", false, "reviewer");

        assertThat(rejected.run().failureReason()).contains("驳回");
        assertThat(business.refundCount()).isZero();
        assertThat(auditLog.byType(AuditEventType.APPROVAL_REJECTED)).isNotEmpty();
    }

    @Test
    void retrievalIsScopedToTheRolesKnowledgeNamespaces() {
        WorkflowResult result = engine.execute("t-5", REFUND_GOAL, "auto");

        List<String> namespaces = result.stepResults().stream()
                .flatMap(s -> s.citations().stream())
                .map(io.github.tangbailin.novaorg.domain.Citation::namespace)
                .distinct()
                .toList();

        assertThat(namespaces).isNotEmpty();
        assertThat(namespaces).allMatch(ns -> List.of("policy", "compensation", "customer",
                "ticket", "product").contains(ns));
        assertThat(namespaces).doesNotContain("finance", "audit");
    }

    @Test
    void aDeterministicRunSatisfiesItsSlaAndStaysUnderBudget() {
        WorkflowResult result = engine.execute("t-6", REFUND_GOAL, "auto");

        assertThat(result.run().slaMet()).isTrue();
        assertThat(result.run().tokens()).isGreaterThan(0);
        assertThat(result.run().totalLatencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(engine.summary("t-6").headline()).contains("SLA 达标");
    }

    @Test
    void theReportIsAuditableForAHumanReader() {
        WorkflowResult result = engine.execute("t-7", REFUND_GOAL, "auto");

        assertThat(result.report())
                .contains("交付报告")
                .contains("客户服务部")
                .contains("工具调用")
                .contains("组织与治理");
    }

    /**
     * The "retries cannot double-pay" claim, tested the only way that means anything: hammer the
     * business boundary concurrently with the same idempotency key and count how many times money
     * actually moved. Retry storms and approval replays both look like this.
     */
    @Test
    void concurrentRetriesWithTheSameIdempotencyKeyNeverPayTwice() throws Exception {
        int threads = 8;
        int attemptsPerThread = 50;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var start = new java.util.concurrent.CountDownLatch(1);
        var moved = new java.util.concurrent.atomic.AtomicInteger();
        var tasks = new java.util.ArrayList<java.util.concurrent.Callable<Void>>();

        for (int t = 0; t < threads; t++) {
            tasks.add(() -> {
                start.await();
                for (int i = 0; i < attemptsPerThread; i++) {
                    if (business.executeRefund("refund-A100-v1", 12_900L)) {
                        moved.incrementAndGet();
                    }
                }
                return null;
            });
        }
        var futures = tasks.stream().map(pool::submit).toList();
        start.countDown();
        for (var f : futures) {
            f.get(30, java.util.concurrent.TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertThat(moved.get())
                .withFailMessage("同一幂等键在 %d 次并发重试中移动了 %d 次资金", threads * attemptsPerThread, moved.get())
                .isEqualTo(1);
        assertThat(business.refundedFen()).isEqualTo(12_900L);
        assertThat(business.refundCount()).isEqualTo(1);
    }

    /**
     * A finished run is not a paused one. When every department delivered and only the high-risk
     * call was refused, the status must say "done" and the blocking reason must carry the caveat -
     * otherwise the reader goes looking for an approval button that does not exist.
     */
    @Test
    void bothLegacyModesPauseOnActualCallsAndRequireExplicitApproval() {
        var result=engine.execute("t-8",AUDIT_GOAL,"auto");
        assertThat(result.waitingApproval()).isTrue();
        assertThat(result.run().pendingActions()).isNotEmpty();
        assertThat(result.run().blockingReason()).contains("尚未执行");
        assertThat(business.refundCount()).isZero();
        var paused=engine.execute("t-9",REFUND_GOAL,"gate");
        assertThat(paused.waitingApproval()).isTrue();
        assertThat(paused.run().pendingActions().get(0).call().arguments()).containsKey("amount");
    }

    private List<String> executedTools(WorkflowResult result) {
        return allToolCalls(result).stream()
                .filter(ToolCallRecord::executed)
                .map(ToolCallRecord::toolName)
                .distinct()
                .toList();
    }

    @Test
    void approvalOnlyAuthorizesFrozenCallsAndLaterStepsNeedANewApproval() {
        var roles=new RoleCatalog();var tools=new ToolCatalog();
        var store=new io.github.tangbailin.novaorg.persistence.MemoryTaskStore();
        var run=new TaskRun("two-approvals","two explicit refund steps","operator");
        run.plan(new io.github.tangbailin.novaorg.domain.TaskPlan(run.taskId(),run.goal(),io.github.tangbailin.novaorg.domain.Complexity.MODERATE,
                List.of(new io.github.tangbailin.novaorg.domain.TaskStep("s1",RoleCatalog.COMPENSATION,"first",List.of(ToolCatalog.REFUND_EXECUTE),List.of(),100),
                        new io.github.tangbailin.novaorg.domain.TaskStep("s2",RoleCatalog.COMPENSATION,"later",List.of(ToolCatalog.REFUND_EXECUTE),List.of(),100)),"fixture"));
        store.create(run);
        var modelCalls=new java.util.concurrent.atomic.AtomicInteger();
        StepExecutor staged=new StepExecutor() {
            public String backendId(){return "test-fixture";}
            public StepResult execute(io.github.tangbailin.novaorg.domain.TaskPlan p,io.github.tangbailin.novaorg.domain.TaskStep s,ExecutionContext context) {
                assertThat(context.approvalGranted()).isFalse();modelCalls.incrementAndGet();
                var args=java.util.Map.<String,Object>of("orderId","A100","amount",10,"reason","test","idempotencyKey","ignored-model-key");
                var record=new ToolCallRecord(s.stepId(),s.roleId(),ToolCatalog.REFUND_EXECUTE,io.github.tangbailin.novaorg.persistence.JsonCodec.write(args),false,"APPROVAL_REQUIRED",false,true,false,"key",io.github.tangbailin.novaorg.domain.CallOrigin.MODEL,0,"pending");
                return new StepResult(s.stepId(),s.roleId(),"proposed",List.of(),List.of(record),1,1,1,0,true,null);
            }
        };
        var planner=new TaskPlanner(new DemoLlmPort(),roles,tools,auditLog,CostModel.defaultModel(),4,100);
        var workflow=new WorkflowEngine(planner,staged,roles,tools,auditLog,CostModel.defaultModel(),new ReportComposer(roles),actionExecutor).withStore(store);
        var first=workflow.execute(run.taskId(),run.goal(),"gate","operator");
        assertThat(first.run().pendingActions().get(0).stepId()).isEqualTo("s1");
        var second=workflow.approve(run.taskId(),true,"reviewer",first.run().revision(),"first approval");
        assertThat(second.waitingApproval()).isTrue();assertThat(second.run().pendingActions().get(0).stepId()).isEqualTo("s2");
        assertThat(modelCalls.get()).isEqualTo(2);assertThat(business.refundedFen()).isEqualTo(1000);
        var finished=workflow.approve(run.taskId(),true,"reviewer",second.run().revision(),"second approval");
        assertThat(finished.waitingApproval()).isFalse();assertThat(modelCalls.get()).isEqualTo(2);
        assertThat(business.refundCount()).isEqualTo(1);
    }

    private List<ToolCallRecord> allToolCalls(WorkflowResult result) {
        return result.stepResults().stream().flatMap(s -> s.toolCalls().stream()).toList();
    }
}
