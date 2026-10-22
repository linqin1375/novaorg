package io.github.tangbailin.novaorg.domain;

/** Outcome of a tool invocation, including refusals so the agent can adapt its plan. */
public record ToolResult(boolean success, String payload, String errorCode) {

    public static ToolResult ok(String payload) {
        return new ToolResult(true, payload, null);
    }

    public static ToolResult error(String errorCode, String message) {
        return new ToolResult(false, message, errorCode);
    }
}
