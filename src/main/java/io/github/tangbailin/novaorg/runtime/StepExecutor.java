package io.github.tangbailin.novaorg.runtime;

import io.github.tangbailin.novaorg.domain.TaskPlan;
import io.github.tangbailin.novaorg.domain.TaskStep;

/**
 * Strategy for executing one work order. The runtime depends on this interface only, which is
 * what lets the deterministic demo backend and a live model backend share the same
 * permission / audit / metrics machinery (and lets tests run with no model at all).
 */
public interface StepExecutor {

    StepResult execute(TaskPlan plan, TaskStep step, ExecutionContext context);

    String backendId();

    /** Per-step knobs the runtime controls: token ceiling and whether high-risk calls are allowed. */
    record ExecutionContext(boolean approvalGranted, String traceNote) {
        public static ExecutionContext of(boolean approvalGranted) {
            return new ExecutionContext(approvalGranted, null);
        }
    }
}
