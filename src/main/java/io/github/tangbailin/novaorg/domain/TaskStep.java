package io.github.tangbailin.novaorg.domain;

import java.util.ArrayList;
import java.util.List;

/** One work order handed to one role. */
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility=com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility=com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility=com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
public final class TaskStep {

    private final String stepId;
    private final String roleId;
    private final String objective;
    private final List<String> allowedTools;
    private final List<String> knowledgeNamespaces;
    private final int budgetTokens;

    private TaskStatus status = TaskStatus.CREATED;
    private String output = "";
    private List<Citation> citations = new ArrayList<>();
    private List<String> toolCallSummary = new ArrayList<>();
    private int retriesUsed;
    private long latencyMs;
    private int tokensUsed;
    private String failureReason;

    @com.fasterxml.jackson.annotation.JsonCreator
    public TaskStep(@com.fasterxml.jackson.annotation.JsonProperty("stepId") String stepId, @com.fasterxml.jackson.annotation.JsonProperty("roleId") String roleId, @com.fasterxml.jackson.annotation.JsonProperty("objective") String objective,
                    @com.fasterxml.jackson.annotation.JsonProperty("allowedTools") List<String> allowedTools, @com.fasterxml.jackson.annotation.JsonProperty("knowledgeNamespaces") List<String> knowledgeNamespaces,
                    @com.fasterxml.jackson.annotation.JsonProperty("budgetTokens") int budgetTokens) {
        this.stepId = stepId;
        this.roleId = roleId;
        this.objective = objective;
        this.allowedTools = List.copyOf(allowedTools);
        this.knowledgeNamespaces = List.copyOf(knowledgeNamespaces);
        this.budgetTokens = budgetTokens;
    }

    public String stepId() {
        return stepId;
    }

    public String roleId() {
        return roleId;
    }

    public String objective() {
        return objective;
    }

    public List<String> allowedTools() {
        return allowedTools;
    }

    public List<String> knowledgeNamespaces() {
        return knowledgeNamespaces;
    }

    public int budgetTokens() {
        return budgetTokens;
    }

    public TaskStatus status() {
        return status;
    }

    public void status(TaskStatus status) {
        this.status = status;
    }

    public String output() {
        return output;
    }

    public void output(String output) {
        this.output = output;
    }

    public List<Citation> citations() {
        return citations;
    }

    public void citations(List<Citation> citations) {
        this.citations = citations;
    }

    public List<String> toolCallSummary() {
        return toolCallSummary;
    }

    public int retriesUsed() {
        return retriesUsed;
    }

    public void retriesUsed(int retriesUsed) {
        this.retriesUsed = retriesUsed;
    }

    public long latencyMs() {
        return latencyMs;
    }

    public void latencyMs(long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public int tokensUsed() {
        return tokensUsed;
    }

    public void tokensUsed(int tokensUsed) {
        this.tokensUsed = tokensUsed;
    }

    public String failureReason() {
        return failureReason;
    }

    public void failureReason(String failureReason) {
        this.failureReason = failureReason;
    }
}
