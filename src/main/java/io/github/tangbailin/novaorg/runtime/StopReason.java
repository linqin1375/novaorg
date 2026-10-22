package io.github.tangbailin.novaorg.runtime;

/** Runtime termination and business success are separate concepts. */
public enum StopReason {
    COMPLETED, WAITING_APPROVAL, NEEDS_INPUT, NO_EVIDENCE,
    TOKEN_BUDGET, TIME_BUDGET, TOOL_BUDGET, MAX_TURNS, REPEATED_CALL, MODEL_ERROR, INVALID_OUTPUT
}
