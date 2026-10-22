package io.github.tangbailin.novaorg.domain;

/** Lifecycle of a delegated sub-task. Mirrors how a real work order moves through an org. */
public enum TaskStatus {
    CREATED,
    ASSIGNED,
    RUNNING,
    WAITING_APPROVAL,
    NEEDS_INPUT,
    DONE,
    REJECTED,
    FAILED
}
