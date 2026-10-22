package io.github.tangbailin.novaorg.policy;

import io.github.tangbailin.novaorg.domain.ToolCall;
import io.github.tangbailin.novaorg.domain.ToolResult;

public interface ActionExecutor {

    /** Runs the full policy pipeline for one requested call and returns what happened. */
    Decision invoke(String taskId, String stepId, ToolCall call, boolean approvalGranted);

    default Decision invoke(String taskId, String stepId, ToolCall call, boolean approved, java.util.List<String> scope) {
        return invoke(taskId, stepId, call, approved);
    }


    record Decision(ToolResult result, boolean approvalRequired, String idempotencyKey,
                    String denyReason, boolean repeated) {

        public boolean allowed() {
            return denyReason == null;
        }
    }
}
