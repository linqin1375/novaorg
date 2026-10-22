package io.github.tangbailin.novaorg.api;

import java.util.List;

/** API projections. Kept flat and explicit so the console contract is obvious from the code. */
public final class Dtos {

    private Dtos() {
    }

    public record CreateTaskRequest(String goal, String requestedBy, String approvalMode) {
    }

    public record ApprovalRequest(boolean granted, String approver, String reason, Long revision) {
    }
    public record ClarifyRequest(String answer, Long revision) {}

    public record RoleView(String id, String name, String department, String mission,
                           List<String> permissions, List<String> knowledgeNamespaces,
                           List<String> deliverableKeys, int maxRetries) {
    }

    public record ToolView(String name, String description, java.util.Map<String, String> parameters,
                           List<String> requiredParameters, String requiredPermission,
                           String risk, boolean idempotent) {
    }

    public record CitationView(String docId, String title, String namespace, int chunkIndex, double score) {
    }

    public record ToolCallView(String toolName, String arguments, boolean allowed, boolean executed,
                               boolean approvalRequired, boolean repeated, String denyReason,
                               String origin, String payload) {
    }

    public record StepView(String stepId, String roleId, String roleName, String department,
                           String objective, String status, List<String> allowedTools,
                           String output, List<CitationView> citations, List<ToolCallView> toolCalls,
                           long latencyMs, int tokens, int retriesUsed,
                           boolean approvalRequired, boolean approved, String failureReason,
                           String stopReason, List<io.github.tangbailin.novaorg.runtime.AgentTurn> trace) {
    }

    public record TaskMetricsView(long totalLatencyMs, int totalTokens, double totalCostUsd,
                                  int toolCalls, int toolDenials, int retries,
                                  int steps, int stepsDone, boolean slaMet) {
    }

    public record TaskView(String taskId, String goal, String complexity, String status,
                           String planRationale, boolean slaMet, TaskMetricsView metrics,
                           List<StepView> steps, String report, String approvalMode,
                           String pendingApprovalReason, String blockingReason, String approvedBy,
                           String backend, String embedding, String failureReason, String requestedBy, long revision, List<io.github.tangbailin.novaorg.runtime.PendingAction> pendingActions,
                           String inputQuestion) {
    }

    public record AuditEventView(String at, String traceId, String taskId, String stepId, String roleId,
                                 String type, String summary, java.util.Map<String, Object> attributes) {
    }

    public record RoleMetricsView(String roleId, String roleName, String department, int tasks,
                                  int steps, int tokens, double costUsd, long avgLatencyMs,
                                  int toolCalls, int toolDenials, int retries, double slaMetRate) {
    }

    public record OrgChartView(List<DepartmentView> departments, int totalRoles, int totalTools) {
    }

    public record DepartmentView(String department, List<RoleView> roles) {
    }

    public record SystemView(String llmBackend, boolean liveModel, String embedding,
                             int knowledgeChunks, int roles, int tools) {
    }
}
