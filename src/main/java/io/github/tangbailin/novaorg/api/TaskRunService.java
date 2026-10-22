package io.github.tangbailin.novaorg.api;

import io.github.tangbailin.novaorg.audit.AuditLog;
import io.github.tangbailin.novaorg.config.NovaOrgProperties;
import io.github.tangbailin.novaorg.runtime.ReportComposer;
import io.github.tangbailin.novaorg.runtime.StepResult;
import io.github.tangbailin.novaorg.runtime.TaskRun;
import io.github.tangbailin.novaorg.runtime.WorkflowEngine;
import io.github.tangbailin.novaorg.runtime.WorkflowResult;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;

/** Application service behind the HTTP API. Thin on purpose: all logic lives in the runtime. */
@Service
public class TaskRunService {

    private final WorkflowEngine engine;
    private final AuditLog auditLog;
    private final ViewMapper mapper;
    private final ReportComposer reportComposer;
    private final NovaOrgProperties properties;
    private final AtomicLong sequence = new AtomicLong();

    public TaskRunService(WorkflowEngine engine, AuditLog auditLog, ViewMapper mapper,
                          ReportComposer reportComposer, NovaOrgProperties properties) {
        this.engine = engine;
        this.auditLog = auditLog;
        this.mapper = mapper;
        this.reportComposer = reportComposer;
        this.properties = properties;
    }

    public Dtos.TaskView submitTask(String goal, String requestedBy, String approvalMode) {
        String taskId = "t-" + java.util.UUID.randomUUID();
        String requester = requestedBy == null || requestedBy.isBlank() ? "anonymous" : requestedBy;
        WorkflowResult result = engine.execute(taskId, goal,
                approvalMode == null || approvalMode.isBlank() ? "auto" : approvalMode, requester);
        return mapper.task(result);
    }

    /** Raised when the four-eyes rule rejects an approval; mapped to HTTP 409 by the controller. */
    public static class SeparationOfDutiesException extends RuntimeException {
        public SeparationOfDutiesException(String message) {
            super(message);
        }
    }

    public List<Dtos.TaskView> listTasks() {
        return engine.allRuns().stream()
                .filter(r -> TaskAccess.reviewer() || TaskAccess.user().equals(r.requestedBy()))
                .sorted(Comparator.comparingLong(TaskRun::createdAt).reversed())
                .map(this::view)
                .toList();
    }

    public Dtos.TaskView getTask(String taskId) {
        TaskRun run = engine.run(taskId);
        if (run == null) {
            throw new TaskNotFoundException(taskId);
        }
        TaskAccess.check(run.requestedBy());
        return view(run);
    }

    /**
     * Rebuilds the same view a fresh execution would have returned - including the delivery
     * report. A read must not be lossy: a task pulled from the history list has to show exactly
     * what the submit response showed, otherwise the console silently drops the report on reload.
     */
    private Dtos.TaskView view(TaskRun run) {
        List<StepResult> results = List.copyOf(run.results().values());
        boolean waitingApproval = run.awaitingApproval();
        String report = reportComposer.compose(run, results, waitingApproval);
        return mapper.task(new WorkflowResult(run, run.plan(), results, waitingApproval, report));
    }

    public Dtos.TaskView approve(String taskId, boolean granted, String approver, String reason, Long revision) {
        if(revision==null) throw new IllegalArgumentException("审批必须携带 revision");
        getTask(taskId);
        String who=approver;
        try {
            WorkflowResult result = engine.approve(taskId, granted, who,
                    revision,reason);
            return mapper.task(result);
        } catch (IllegalStateException e) {
            throw new SeparationOfDutiesException(e.getMessage());
        }
    }

    public Dtos.TaskView clarify(String taskId,String answer,Long revision) {
        Dtos.TaskView task=getTask(taskId);
        if(!TaskAccess.user().equals(task.requestedBy())) throw new org.springframework.security.access.AccessDeniedException("仅申请人可补充任务信息");
        if(revision==null) throw new IllegalArgumentException("必须携带 revision");
        try { return mapper.task(engine.clarify(taskId,answer,revision)); }
        catch(IllegalStateException e) { throw new SeparationOfDutiesException(e.getMessage()); }
    }

    public List<Dtos.AuditEventView> audit(String taskId, String roleId, String type) {
        return auditEvents(taskId, roleId, type).stream().map(mapper::audit).toList();
    }

    /** Raw events, newest first - used by both the JSON endpoint and the export formats. */
    public List<io.github.tangbailin.novaorg.audit.AuditEvent> auditEvents(String taskId, String roleId,
                                                                          String type) {
        if(taskId!=null && !taskId.isBlank()) getTask(taskId);
        else if(!TaskAccess.reviewer()) throw new org.springframework.security.access.AccessDeniedException("仅审核员或管理员可查询全局审计");
        return auditLog.all().stream()
                .filter(e -> taskId == null || taskId.isBlank() || taskId.equals(e.taskId()))
                .filter(e -> roleId == null || roleId.isBlank() || roleId.equals(e.roleId()))
                .filter(e -> type == null || type.isBlank() || type.equalsIgnoreCase(e.type().name()))
                .sorted(Comparator.comparing(io.github.tangbailin.novaorg.audit.AuditEvent::at).reversed())
                .toList();
    }

    /**
     * Exporting the audit trail is itself an auditable act - who pulled which slice, in what
     * format, and how many records left the system.
     */
    public void recordAuditExport(String requestedBy, String taskId, String roleId, String type,
                                  String format, int recordCount) {
        auditLog.record(io.github.tangbailin.novaorg.audit.AuditEvent.of(
                taskId, null, "compliance_officer",
                io.github.tangbailin.novaorg.audit.AuditEventType.AUDIT_EXPORTED,
                "审计日志已导出（" + format + "），共 " + recordCount + " 条",
                java.util.Map.of(
                        "requestedBy", requestedBy == null || requestedBy.isBlank() ? "anonymous" : requestedBy,
                        "format", format,
                        "filterTaskId", String.valueOf(taskId),
                        "filterRoleId", String.valueOf(roleId),
                        "filterType", String.valueOf(type),
                        "records", recordCount)));
    }

    public List<Dtos.RoleMetricsView> roleMetrics() {
        return mapper.roleMetrics(List.copyOf(engine.allRuns()));
    }

    public Dtos.TaskView resume(String taskId) {
        getTask(taskId);
        TaskRun run=engine.run(taskId);
        return mapper.task(engine.execute(taskId,run.goal(),"gate",run.requestedBy()));
    }

    public static class TaskNotFoundException extends RuntimeException {
        public TaskNotFoundException(String taskId) {
            super("unknown task: " + taskId);
        }
    }
}
