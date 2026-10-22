package io.github.tangbailin.novaorg.api;

import io.github.tangbailin.novaorg.audit.AuditEvent;
import io.github.tangbailin.novaorg.domain.Citation;
import io.github.tangbailin.novaorg.domain.Role;
import io.github.tangbailin.novaorg.domain.TaskStep;
import io.github.tangbailin.novaorg.domain.ToolCallRecord;
import io.github.tangbailin.novaorg.domain.ToolSpec;
import io.github.tangbailin.novaorg.knowledge.KnowledgeBase;
import io.github.tangbailin.novaorg.llm.LlmPort;
import io.github.tangbailin.novaorg.metrics.CostModel;
import io.github.tangbailin.novaorg.org.RoleCatalog;
import io.github.tangbailin.novaorg.org.ToolCatalog;
import io.github.tangbailin.novaorg.runtime.StepResult;
import io.github.tangbailin.novaorg.runtime.TaskRun;
import io.github.tangbailin.novaorg.runtime.WorkflowResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/** Domain -> API projection, in one place so the HTTP contract is easy to audit. */
@Component
public class ViewMapper {

    private final RoleCatalog roleCatalog;
    private final ToolCatalog toolCatalog;
    private final CostModel costModel;
    private final KnowledgeBase knowledgeBase;
    private final LlmPort llmPort;

    public ViewMapper(RoleCatalog roleCatalog, ToolCatalog toolCatalog, CostModel costModel,
                      KnowledgeBase knowledgeBase, LlmPort llmPort) {
        this.roleCatalog = roleCatalog;
        this.toolCatalog = toolCatalog;
        this.costModel = costModel;
        this.knowledgeBase = knowledgeBase;
        this.llmPort = llmPort;
    }

    public Dtos.RoleView role(Role role) {
        return new Dtos.RoleView(role.id(), role.name(), role.department(), role.mission(),
                role.permissions().stream().map(p -> p.code()).sorted().toList(),
                role.knowledgeNamespaces().stream().sorted().toList(),
                role.deliverableKeys(), role.maxRetries());
    }

    public Dtos.ToolView tool(ToolSpec spec) {
        return new Dtos.ToolView(spec.name(), spec.description(), spec.parameters(),
                spec.requiredParameters(), spec.requiredPermission().code(),
                spec.risk().name(), spec.idempotent());
    }

    public Dtos.AuditEventView audit(AuditEvent event) {
        return new Dtos.AuditEventView(
                event.at().toString(), event.traceId(), event.taskId(), event.stepId(), event.roleId(),
                event.type().name(), event.summary(), event.attributes());
    }

    public Dtos.TaskView task(WorkflowResult result) {
        TaskRun run = result.run();
        List<Dtos.StepView> steps = new ArrayList<>();
        for (TaskStep step : run.steps()) {
            StepResult stepResult = run.results().get(step.stepId());
            Role role = roleCatalog.find(step.roleId()).orElse(null);
            steps.add(new Dtos.StepView(
                    step.stepId(),
                    step.roleId(),
                    role == null ? step.roleId() : role.name(),
                    role == null ? "-" : role.department(),
                    step.objective(),
                    step.status().name(),
                    step.allowedTools(),
                    stepResult == null ? "" : stepResult.output(),
                    stepResult == null ? List.of() : stepResult.citations().stream().map(this::citation).toList(),
                    stepResult == null ? List.of() : stepResult.toolCalls().stream().map(this::toolCall).toList(),
                    stepResult == null ? 0 : stepResult.latencyMs(),
                    stepResult == null ? 0 : stepResult.totalTokens(),
                    stepResult == null ? 0 : stepResult.retriesUsed(),
                    step.status()==io.github.tangbailin.novaorg.domain.TaskStatus.WAITING_APPROVAL,
                    run.approvedActions().stream().anyMatch(a->a.stepId().equals(step.stepId())),
                    stepResult == null ? null : stepResult.failureReason(),
                    stepResult == null ? null : stepResult.stopReason().name(),
                    stepResult == null ? List.of() : stepResult.trace()));
        }

        int stepsDone = (int) run.steps().stream().filter(s->s.status()==io.github.tangbailin.novaorg.domain.TaskStatus.DONE).count();
        Dtos.TaskMetricsView metrics = new Dtos.TaskMetricsView(
                run.totalLatencyMs(), run.tokens(), costModel.cost(run.tokens()),
                run.toolCalls(), run.denials(), run.retries(),
                run.steps().size(), stepsDone, run.slaMet());

        return new Dtos.TaskView(
                run.taskId(), run.goal(),
                run.plan() == null ? "SIMPLE" : run.plan().complexity().name(),
                status(run, result),
                run.plan() == null ? "" : run.plan().rationale(),
                run.slaMet(), metrics, steps, result.report(),
                run.approvalMode(), run.pendingApprovalReason(), run.blockingReason(), run.approvedBy(),
                llmPort.id(), knowledgeBase.embeddingId(), run.failureReason(), run.requestedBy(), run.revision(), run.pendingActions(),run.inputQuestion());
    }

    private String status(TaskRun run, WorkflowResult result) {
        if(run.awaitingInput()) return "NEEDS_INPUT";
        if (run.failureReason() != null && "审批被驳回".equals(run.failureReason())) {
            return "REJECTED";
        }
        if (run.failureReason()!=null) return "FAILED";
        if (run.awaitingApproval()) return "WAITING_APPROVAL";
        if (run.allStepsDone()) {
            // Finished is finished: if a high-risk action inside it was refused, that shows up as
            // blockingReason and as a PENDING_REVIEW step detail - not as "waiting for approval",
            // which would send the reader looking for an approval button that does not exist.
            return "DONE";
        }
        if (result.waitingApproval()) {
            return "WAITING_APPROVAL";
        }
        if (run.blockingReason() != null || run.anyStepWaitingApproval()) {
            return "PENDING_REVIEW";
        }
        return "RUNNING";
    }

    public Dtos.CitationView citation(Citation citation) {
        return new Dtos.CitationView(citation.docId(), citation.title(), citation.namespace(),
                citation.chunkIndex(), citation.score());
    }

    public Dtos.ToolCallView toolCall(ToolCallRecord record) {
        return new Dtos.ToolCallView(record.toolName(), record.arguments(), record.allowed(),
                record.executed(), record.approvalRequired(), record.repeated(),
                record.denyReason(), record.origin().name(), record.payloadPreview());
    }

    public Dtos.SystemView system() {
        return new Dtos.SystemView(llmPort.id(), llmPort.isLive(), knowledgeBase.embeddingId(),
                knowledgeBase.chunkCount(), roleCatalog.all().size(), toolCatalog.all().size());
    }

    public List<Dtos.RoleMetricsView> roleMetrics(List<TaskRun> runs) {
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, double[]> totals = new LinkedHashMap<>();
        Map<String, Long> latencySum = new LinkedHashMap<>();
        Map<String, Integer> latencyCount = new LinkedHashMap<>();
        Map<String, Integer> slaMet = new LinkedHashMap<>();
        Map<String, Integer> taskSeen = new LinkedHashMap<>();

        for (TaskRun run : runs) {
            boolean met = run.slaMet();
            for (StepResult r : run.results().values()) {
                counts.computeIfAbsent(r.roleId(), k -> new int[4]);
                int[] c = counts.get(r.roleId());
                c[0] += 1;                              // steps
                c[1] += r.toolCalls().size();           // tool calls
                c[2] += r.deniedToolCalls();            // denials
                c[3] += r.retriesUsed();
                totals.computeIfAbsent(r.roleId(), k -> new double[2]);
                totals.get(r.roleId())[0] += r.totalTokens();
                totals.get(r.roleId())[1] += costModel.cost(r.totalTokens());
                latencySum.merge(r.roleId(), r.latencyMs(), Long::sum);
                latencyCount.merge(r.roleId(), 1, Integer::sum);
                slaMet.merge(r.roleId(), met ? 1 : 0, Integer::sum);
                taskSeen.merge(r.roleId(), 1, Integer::sum);
            }
        }

        List<Dtos.RoleMetricsView> out = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : counts.entrySet()) {
            String roleId = entry.getKey();
            int[] c = entry.getValue();
            Role role = roleCatalog.find(roleId).orElse(null);
            double[] tokensAndCost = totals.get(roleId);
            long avg = latencyCount.get(roleId) == 0 ? 0 : latencySum.get(roleId) / latencyCount.get(roleId);
            double rate = c[0] == 0 ? 1.0 : (double) slaMet.getOrDefault(roleId, 0) / c[0];
            out.add(new Dtos.RoleMetricsView(
                    roleId,
                    role == null ? roleId : role.name(),
                    role == null ? "-" : role.department(),
                    taskSeen.getOrDefault(roleId, 0),
                    c[0],
                    (int) tokensAndCost[0],
                    round(tokensAndCost[1]),
                    avg,
                    c[1],
                    c[2],
                    c[3],
                    round(rate)));
        }
        out.sort(java.util.Comparator.comparing(Dtos.RoleMetricsView::roleId));
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
