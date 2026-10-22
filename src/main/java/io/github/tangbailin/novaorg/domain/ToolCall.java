package io.github.tangbailin.novaorg.domain;

import java.util.Map;

/** One model-requested tool invocation, before policy checks. */
public record ToolCall(String toolName, Map<String, Object> arguments, String requestedByRole) {
}
