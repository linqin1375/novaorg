package io.github.tangbailin.novaorg.runtime;

import io.github.tangbailin.novaorg.domain.ToolCall;

public record PendingAction(String stepId, ToolCall call) {}
