package io.github.tangbailin.novaorg.runtime;

import io.github.tangbailin.novaorg.domain.Citation;
import io.github.tangbailin.novaorg.domain.ToolCallRecord;
import java.util.List;

/** One role's output, evidence, tool decisions and explicit termination state. */
public record StepResult(String stepId,String roleId,String output,List<Citation> citations,
                         List<ToolCallRecord> toolCalls,int inputTokens,int outputTokens,long latencyMs,
                         int retriesUsed,boolean approvalRequired,String failureReason,
                         StopReason stopReason,List<AgentTurn> trace) {
    public StepResult {
        citations = citations == null ? List.of() : List.copyOf(citations);
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        trace = trace == null ? List.of() : List.copyOf(trace);
        if (stopReason == null) stopReason = approvalRequired ? StopReason.WAITING_APPROVAL
                : failureReason == null ? StopReason.COMPLETED : StopReason.MODEL_ERROR;
    }
    public StepResult(String stepId,String roleId,String output,List<Citation> citations,List<ToolCallRecord> calls,
                      int in,int out,long latency,int retries,boolean approval,String failure) {
        this(stepId,roleId,output,citations,calls,in,out,latency,retries,approval,failure,null,List.of());
    }
    public int totalTokens() { return inputTokens+outputTokens; }
    public boolean succeeded() { return failureReason == null &&
            (stopReason == StopReason.COMPLETED || stopReason == StopReason.WAITING_APPROVAL); }
    public int deniedToolCalls() { return (int) toolCalls.stream().filter(c -> !c.allowed()).count(); }
}
