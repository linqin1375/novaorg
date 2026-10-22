package io.github.tangbailin.novaorg.runtime;

import io.github.tangbailin.novaorg.audit.AuditEvent;
import io.github.tangbailin.novaorg.audit.AuditEventType;
import io.github.tangbailin.novaorg.audit.AuditLog;
import io.github.tangbailin.novaorg.domain.Complexity;
import io.github.tangbailin.novaorg.domain.Role;
import io.github.tangbailin.novaorg.domain.TaskPlan;
import io.github.tangbailin.novaorg.domain.TaskStep;
import io.github.tangbailin.novaorg.domain.ToolSpec;
import io.github.tangbailin.novaorg.metrics.CostModel;
import io.github.tangbailin.novaorg.metrics.SlaPolicy;
import io.github.tangbailin.novaorg.org.RoleCatalog;
import io.github.tangbailin.novaorg.org.ToolCatalog;
import io.github.tangbailin.novaorg.plan.TaskPlanner;
import io.github.tangbailin.novaorg.policy.DefaultActionExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Durable plan -> execute safe calls -> freeze risky calls -> human review -> execute frozen calls.
 * Legacy auto/gate inputs both follow the same mandatory approval boundary.
 * Each mutation is serialized by TaskStore; the JDBC store shares its transaction with audit and ledger writes.
 */
public final class WorkflowEngine {

    private final TaskPlanner planner;
    private final StepExecutor stepExecutor;
    private final RoleCatalog roleCatalog;
    private final ToolCatalog toolCatalog;
    private final AuditLog auditLog;
    private final CostModel costModel;
    private final ReportComposer reportComposer;
    private final DefaultActionExecutor actionExecutor;

    private io.github.tangbailin.novaorg.persistence.TaskStore store = new io.github.tangbailin.novaorg.persistence.MemoryTaskStore();
    public WorkflowEngine withStore(io.github.tangbailin.novaorg.persistence.TaskStore store) { this.store=store; return this; }

    public WorkflowEngine(TaskPlanner planner, StepExecutor stepExecutor, RoleCatalog roleCatalog,
                          ToolCatalog toolCatalog, AuditLog auditLog, CostModel costModel,
                          ReportComposer reportComposer, DefaultActionExecutor actionExecutor) {
        this.planner = planner;
        this.stepExecutor = stepExecutor;
        this.roleCatalog = roleCatalog;
        this.toolCatalog = toolCatalog;
        this.auditLog = auditLog;
        this.costModel = costModel;
        this.reportComposer = reportComposer;
        this.actionExecutor = actionExecutor;
    }

    public WorkflowResult execute(String taskId, String goal) {
        return execute(taskId, goal, "auto", "unknown");
    }

    public WorkflowResult execute(String taskId, String goal, String approvalMode) {
        return execute(taskId, goal, approvalMode, "unknown");
    }

    public WorkflowResult execute(String taskId, String goal, String approvalMode, String requestedBy) {
        store.create(new TaskRun(taskId, goal, requestedBy));
        return store.update(taskId, run -> {
            if (run.awaitingApproval() || run.awaitingInput() || run.failureReason()!=null || run.allStepsDone()) return snapshot(run,run.awaitingApproval());
            run.approvalMode("gate");
            if (run.plan()==null) run.plan(planner.plan(taskId,goal));
            boolean pause=runSteps(run,"gate");
            if (!pause && !run.awaitingInput() && run.failureReason()==null) finalizeRun(run);
            run.bumpRevision();
            return snapshot(run,pause);
        });
    }

    private boolean runSteps(TaskRun run, String approvalMode) {
        while (run.nextStepIndex()<run.steps().size()) {
            TaskStep step=run.steps().get(run.nextStepIndex());
            step.status(io.github.tangbailin.novaorg.domain.TaskStatus.RUNNING);
            StepResult result=stepExecutor.execute(run.plan(),step,new StepExecutor.ExecutionContext(false,"task="+run.taskId()));
            step.output(result.output()); step.citations(result.citations());
            step.latencyMs(result.latencyMs()); step.tokensUsed(result.totalTokens());
            run.record(step,result);
            if(result.stopReason()==StopReason.NEEDS_INPUT) {
                step.status(io.github.tangbailin.novaorg.domain.TaskStatus.NEEDS_INPUT);
                run.inputQuestion(result.output());run.blockingReason("需要用户补充信息");
                return false;
            }
            run.advance();
            if (!result.succeeded()) {
                step.status(io.github.tangbailin.novaorg.domain.TaskStatus.FAILED);
                return false;
            }
            var pending=new ArrayList<PendingAction>();
            for(var record:result.toolCalls()) if(record.approvalRequired()) {
                @SuppressWarnings("unchecked") Map<String,Object> args=io.github.tangbailin.novaorg.persistence.JsonCodec.read(record.arguments(),Map.class);
                var call=new io.github.tangbailin.novaorg.domain.ToolCall(record.toolName(),args,record.roleId());
                var candidate=new PendingAction(step.stepId(),call);
                if(!pending.contains(candidate)) pending.add(candidate);
            }
            if(!pending.isEmpty()) {
                run.pendingActions(pending);
                step.status(io.github.tangbailin.novaorg.domain.TaskStatus.WAITING_APPROVAL);
                run.pendingApprovalReason("请核对以下具体工具、订单和金额；审批只授权这一批操作。");
                run.blockingReason("待人工审批，尚未执行这些操作");
                return true;
            }
            step.status(io.github.tangbailin.novaorg.domain.TaskStatus.DONE);
        }
        return false;
    }

    private void finalizeRun(TaskRun run) {
        SlaPolicy policy = SlaPolicy.forComplexity(
                run.plan() == null ? Complexity.SIMPLE : run.plan().complexity());
        double cost = costModel.cost(run.tokens());
        boolean slaMet = !policy.latencyExceeded(run.totalLatencyMs())
                && !policy.tokensExceeded(run.tokens())
                && !policy.costExceeded(cost);
        run.slaMet(slaMet);

        if (!slaMet) {
            auditLog.record(AuditEvent.of(run.taskId(), null, RoleCatalog.DELIVERY_MANAGER,
                    AuditEventType.SLA_BREACHED,
                    "SLA 未达标：延迟 %d ms / token %d / 成本 %.4f 美元"
                            .formatted(run.totalLatencyMs(), run.tokens(), cost),
                    Map.of("maxLatencyMs", policy.maxLatencyMs(),
                            "maxTokens", policy.maxTokens(),
                            "maxCostUsd", policy.maxCostUsd())));
        }

        RunSummary summary = summarize(run);
        run.finalAnswer(summary.headline());
        auditLog.record(AuditEvent.of(run.taskId(), null, RoleCatalog.DELIVERY_MANAGER,
                AuditEventType.STEP_FINISHED, "任务收口：" + summary.headline(),
                Map.of("steps", run.steps().size(),
                        "tokens", run.tokens(),
                        "toolCalls", run.toolCalls(),
                        "denials", run.denials(),
                        "slaMet", slaMet)));
    }

    /** Releases the approval gate and resumes the task. */
    public WorkflowResult approve(String taskId, boolean granted, String approver) {
        return approve(taskId, granted, approver, true);
    }

    /**
     * Releases the approval gate and resumes the task.
     *
     * @param enforceFourEyes retained for source compatibility; separation of duties is always enforced
     */
    public WorkflowResult approve(String taskId, boolean granted, String approver,
                                  boolean enforceFourEyes) {
        TaskRun current=store.get(taskId);
        if(current==null) throw new IllegalArgumentException("unknown task: "+taskId);
        return approve(taskId,granted,approver,current.revision(),"");
    }

    public WorkflowResult approve(String taskId, boolean granted, String approver, long revision, String reason) {
        return store.update(taskId,run -> {
            if(run.revision()!=revision || !run.awaitingApproval()) throw new IllegalStateException("审批已处理或版本过期，请刷新任务");
            if(approver==null || approver.isBlank() || approver.equalsIgnoreCase(run.requestedBy())) throw new IllegalStateException("审批人与申请人必须不同");
            run.approvedBy(approver);
            auditLog.record(AuditEvent.of(taskId,null,RoleCatalog.DELIVERY_MANAGER,
                    granted?AuditEventType.APPROVAL_GRANTED:AuditEventType.APPROVAL_REJECTED,
                    granted?"已批准指定操作":"审批被驳回",
                    Map.of("actor",approver,"revision",revision,"reason",reason==null?"":reason,"actions",run.pendingActions())));
            if(!granted) {
                run.failureReason("审批被驳回");
                run.steps().stream().filter(x->x.status()==io.github.tangbailin.novaorg.domain.TaskStatus.WAITING_APPROVAL).forEach(x->x.status(io.github.tangbailin.novaorg.domain.TaskStatus.FAILED));
                run.pendingActions(List.of()); run.pendingApprovalReason(null);run.blockingReason(null);
                run.bumpRevision();return snapshot(run,false);
            }
            for(PendingAction pending:run.pendingActions()) {
                TaskStep step=run.steps().stream().filter(x->x.stepId().equals(pending.stepId())).findFirst().orElseThrow();
                run.approvedActions().add(pending);
                var decision=actionExecutor.invoke(taskId,step.stepId(),pending.call(),true,step.allowedTools());
                StepResult old=run.results().get(step.stepId());
                var records=new ArrayList<>(old.toolCalls());
                records.add(new io.github.tangbailin.novaorg.domain.ToolCallRecord(step.stepId(),step.roleId(),pending.call().toolName(),
                        io.github.tangbailin.novaorg.persistence.JsonCodec.write(pending.call().arguments()),decision.allowed(),decision.denyReason(),decision.allowed()&&!decision.repeated(),false,decision.repeated(),decision.idempotencyKey(),io.github.tangbailin.novaorg.domain.CallOrigin.POLICY,0,decision.result().payload()));
                String output="人工审批已处理。\n\n【审批后执行结果】"+decision.result().payload();
                String failure=decision.allowed()?old.failureReason():decision.denyReason();
                var updated=new StepResult(old.stepId(),old.roleId(),output,old.citations(),records,old.inputTokens(),old.outputTokens(),old.latencyMs(),old.retriesUsed(),false,failure,
                        failure==null?StopReason.COMPLETED:StopReason.MODEL_ERROR,old.trace());
                run.results().put(step.stepId(),updated);step.output(output);
                step.status(decision.allowed()?io.github.tangbailin.novaorg.domain.TaskStatus.DONE:io.github.tangbailin.novaorg.domain.TaskStatus.FAILED);
                if(!decision.allowed()) { run.failureReason("审批操作执行失败："+decision.result().payload()); break; }
            }
            run.pendingActions(List.of());run.pendingApprovalReason(null);run.blockingReason(null);
            // Approval is consumed; later model calls always need their own review.
            boolean pause=run.failureReason()==null && runSteps(run,"gate");
            if(!pause && !run.awaitingInput() && run.failureReason()==null) finalizeRun(run);
            run.bumpRevision();return snapshot(run,pause);
        });
    }

    public WorkflowResult clarify(String taskId,String answer,long revision) {
        if(answer==null || answer.isBlank() || answer.length()>2000) throw new IllegalArgumentException("补充信息需为 1–2000 字符");
        return store.update(taskId,run->{
            if(run.revision()!=revision || !run.awaitingInput()) throw new IllegalStateException("任务已更新或当前不需要补充信息");
            if(run.goal().length()+answer.length()>12000) throw new IllegalArgumentException("累计补充信息过长，请新建任务");
            run.appendInput(answer);run.inputQuestion(null);run.blockingReason(null);
            var old=run.plan();
            run.plan(new TaskPlan(old.taskId(),run.goal(),old.complexity(),old.steps(),old.rationale(),old.requestIntent()));
            auditLog.record(AuditEvent.of(taskId,null,null,AuditEventType.INPUT_SUPPLIED,"已补充信息并恢复处理"));
            boolean pause=runSteps(run,"gate");
            if(!pause && !run.awaitingInput() && run.failureReason()==null) finalizeRun(run);
            run.bumpRevision();return snapshot(run,pause);
        });
    }

    private WorkflowResult snapshot(TaskRun run, boolean waitingApproval) {
        List<StepResult> results = new ArrayList<>(run.results().values());
        String report = reportComposer.compose(run, results, waitingApproval);
        return new WorkflowResult(run, run.plan(), results, waitingApproval, report);
    }

    public TaskRun run(String taskId) {
        return store.get(taskId);
    }

    public List<TaskRun> allRuns() {
        return store.all();
    }

    public RunSummary summary(String taskId) {
        TaskRun run = store.get(taskId);
        return run == null ? null : summarize(run);
    }

    /** Aggregates a finished run into the numbers the dashboard and the report quote. */
    public RunSummary summarize(TaskRun run) {
        double cost = costModel.cost(run.tokens());
        long done = run.steps().stream().filter(s->s.status()==io.github.tangbailin.novaorg.domain.TaskStatus.DONE).count();
        String headline = "已完成 %d/%d 个部门交付，工具调用 %d 次（拒绝 %d 次），%d tokens，%.4f 美元%s"
                .formatted(done, run.steps().size(), run.toolCalls(), run.denials(),
                        run.tokens(), cost, run.slaMet() ? "，SLA 达标" : "，SLA 未达标");
        return new RunSummary(run.taskId(), run.goal(),
                run.plan() == null ? "SIMPLE" : run.plan().complexity().name(),
                run.steps().size(), (int) done, run.totalLatencyMs(), run.tokens(), cost,
                run.toolCalls(), run.denials(), run.retries(), run.slaMet(), headline);
    }

    public RoleCatalog roleCatalog() {
        return roleCatalog;
    }

    public Map<String, Object> orgChart() {
        Map<String, Object> root = new java.util.LinkedHashMap<>();
        List<Map<String, Object>> departments = new ArrayList<>();
        Map<String, List<Role>> byDept = new java.util.LinkedHashMap<>();
        for (Role role : roleCatalog.all()) {
            byDept.computeIfAbsent(role.department(), k -> new ArrayList<>()).add(role);
        }
        for (Map.Entry<String, List<Role>> entry : byDept.entrySet()) {
            Map<String, Object> dept = new java.util.LinkedHashMap<>();
            dept.put("department", entry.getKey());
            dept.put("roles", entry.getValue().stream().map(r -> {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("id", r.id());
                m.put("name", r.name());
                m.put("mission", r.mission());
                m.put("permissions", r.permissions().stream().map(p -> p.code()).sorted().toList());
                m.put("knowledgeNamespaces", List.copyOf(r.knowledgeNamespaces()));
                m.put("tools", toolCatalog.forRolePermissions(r.permissions()).stream()
                        .map(ToolSpec::name).sorted().toList());
                return m;
            }).toList());
            departments.add(dept);
        }
        root.put("departments", departments);
        root.put("totalRoles", roleCatalog.all().size());
        root.put("totalTools", toolCatalog.all().size());
        return root;
    }

    public record RunSummary(String taskId, String goal, String complexity, int steps, int stepsDone,
                             long latencyMs, int tokens, double costUsd, int toolCalls, int denials,
                             int retries, boolean slaMet, String headline) {
    }
}
