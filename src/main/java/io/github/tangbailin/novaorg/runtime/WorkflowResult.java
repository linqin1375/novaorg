package io.github.tangbailin.novaorg.runtime;

import io.github.tangbailin.novaorg.domain.TaskPlan;

import java.util.List;

/** Everything a caller (API, eval harness, test) needs after a turn of the workflow. */
public record WorkflowResult(
        TaskRun run,
        TaskPlan plan,
        List<StepResult> stepResults,
        boolean waitingApproval,
        String report
) {
}
