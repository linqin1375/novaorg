package io.github.tangbailin.novaorg.runtime;

import io.github.tangbailin.novaorg.domain.TaskPlan;
import io.github.tangbailin.novaorg.domain.TaskStep;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Live state of one task: plan, per-step results, accumulated metrics and approval status.
 * Persisted as a versioned JSON snapshot, guarded by a database row lock.
 */
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility=com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility=com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility=com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
public final class TaskRun {

    private final String taskId;
    private long createdAt = System.currentTimeMillis();
    public long createdAt() { return createdAt; }
    private String goal;
    private String inputQuestion;
    public String inputQuestion() { return inputQuestion; }
    public void inputQuestion(String question) { inputQuestion=question; }
    public boolean awaitingInput() { return inputQuestion!=null; }
    public void appendInput(String answer) { goal=goal+"\n用户补充："+answer; }
    /** Who asked for this work. Kept because the approval gate needs it for the four-eyes rule. */
    private final String requestedBy;
    private long revision;
    private List<PendingAction> approvedActions = new ArrayList<>();
    public List<PendingAction> approvedActions() { return approvedActions; }
    private List<PendingAction> pendingActions = new ArrayList<>();
    public long revision() { return revision; }
    public void bumpRevision() { revision++; }
    public List<PendingAction> pendingActions() { return pendingActions; }
    public void pendingActions(List<PendingAction> actions) { pendingActions = new ArrayList<>(actions); }
    private TaskPlan plan;
    private int nextStepIndex;
    private boolean approvalGranted;
    private String approvalMode = "auto";
    private String approvedBy;
    private String pendingApprovalReason;
    private String blockingReason;
    private String finalAnswer = "";
    private String failureReason;
    private long totalLatencyMs;
    private int tokens;
    private int denials;
    private int retries;
    private int toolCalls;
    private boolean slaMet = true;

    private final Map<String, StepResult> results = new LinkedHashMap<>();
    private final List<StepResult> history = new ArrayList<>();

    public TaskRun(String taskId, String goal) {
        this(taskId, goal, "unknown");
    }

    @com.fasterxml.jackson.annotation.JsonCreator
    public TaskRun(@com.fasterxml.jackson.annotation.JsonProperty("taskId") String taskId, @com.fasterxml.jackson.annotation.JsonProperty("goal") String goal, @com.fasterxml.jackson.annotation.JsonProperty("requestedBy") String requestedBy) {
        this.taskId = taskId;
        this.goal = goal;
        this.requestedBy = requestedBy == null || requestedBy.isBlank() ? "unknown" : requestedBy;
    }

    public String requestedBy() {
        return requestedBy;
    }

    public String taskId() {
        return taskId;
    }

    public String goal() {
        return goal;
    }

    public TaskPlan plan() {
        return plan;
    }

    public void plan(TaskPlan plan) {
        this.plan = plan;
    }

    public List<TaskStep> steps() {
        return plan == null ? List.of() : plan.steps();
    }

    public int nextStepIndex() {
        return nextStepIndex;
    }

    public void nextStepIndex(int index) {
        this.nextStepIndex = index;
    }

    public int advance() {
        return nextStepIndex++;
    }

    public boolean approvalGranted() {
        return approvalGranted;
    }

    public void approvalGranted(boolean granted) {
        this.approvalGranted = granted;
    }

    public String approvalMode() {
        return approvalMode;
    }

    public void approvalMode(String mode) {
        this.approvalMode = mode;
    }

    public String approvedBy() {
        return approvedBy;
    }

    public void approvedBy(String who) {
        this.approvedBy = who;
    }

    public String pendingApprovalReason() {
        return pendingApprovalReason;
    }

    /**
     * Set when the work finished but at least one high-risk action was refused for lack of an
     * approval. This is a first-class outcome, not a detail buried in a step: the callers (API,
     * console, report) must be able to say "done, but this one thing still needs a human".
     */
    public String blockingReason() {
        return blockingReason;
    }

    public void blockingReason(String reason) {
        this.blockingReason = reason;
    }

    public void pendingApprovalReason(String reason) {
        this.pendingApprovalReason = reason;
    }

    public String finalAnswer() {
        return finalAnswer;
    }

    public void finalAnswer(String answer) {
        this.finalAnswer = answer;
    }

    public String failureReason() {
        return failureReason;
    }

    public void failureReason(String reason) {
        this.failureReason = reason;
    }

    public boolean slaMet() {
        return slaMet;
    }

    public void slaMet(boolean met) {
        this.slaMet = met;
    }

    public long totalLatencyMs() {
        return totalLatencyMs;
    }

    public int tokens() {
        return tokens;
    }

    public int denials() {
        return results.values().stream().mapToInt(StepResult::deniedToolCalls).sum();
    }

    public int retries() {
        return results.values().stream().mapToInt(StepResult::retriesUsed).sum();
    }

    public int toolCalls() {
        return results.values().stream().mapToInt(r->r.toolCalls().size()).sum();
    }

    public Map<String, StepResult> results() {
        return results;
    }

    public List<StepResult> history() {
        return history;
    }

    public void record(TaskStep step, StepResult result) {
        results.put(step.stepId(), result);
        history.add(result);
        totalLatencyMs += result.latencyMs();
        tokens += result.totalTokens();
        toolCalls += result.toolCalls().size();
        denials += result.deniedToolCalls();
        if (!result.succeeded()) {
            failureReason = result.failureReason();
        }
    }

    public boolean allStepsDone() {
        return plan != null && !plan.steps().isEmpty() && plan.steps().stream()
                .allMatch(s->s.status()==io.github.tangbailin.novaorg.domain.TaskStatus.DONE);
    }

    public boolean anyStepWaitingApproval() {
        return results.values().stream().anyMatch(StepResult::approvalRequired);
    }

    /**
     * A step explicitly parked at the approval gate. Needed when a run is re-read after the fact:
     * a step that never executed has no {@link StepResult} yet, so the "we are blocked" signal has
     * to come from the step's own status instead of from a result that does not exist.
     */
    public boolean parkedAtApprovalGate() {
        return plan != null && plan.steps().stream()
                .anyMatch(s -> s.status() == io.github.tangbailin.novaorg.domain.TaskStatus.WAITING_APPROVAL);
    }

    /** The workflow is paused mid-flight and a human has to release it. */
    public boolean awaitingApproval() {
        return failureReason == null && !pendingActions.isEmpty();
    }

    /**
     * Work finished, but at least one high-risk action was refused for lack of an approval.
     * Deliberately distinct from {@link #awaitingApproval()}: nothing is paused here, so the task
     * must not present itself as waiting for input.
     */
    public boolean finishedWithBlockedActions() {
        return false;
    }
}
