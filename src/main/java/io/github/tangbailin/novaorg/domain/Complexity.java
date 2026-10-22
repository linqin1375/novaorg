package io.github.tangbailin.novaorg.domain;

/**
 * Complexity is declared up-front and drives everything downstream: which roles are
 * engaged, which tools may be used, how many tokens/latency the task is allowed to burn.
 * This is the code equivalent of "sizing a project before staffing it".
 */
public enum Complexity {
    /** A single department can close it. */
    SIMPLE,
    /** Several departments, sequential hand-off. */
    MODERATE,
    /** Cross-department, needs an approval gate before side effects. */
    COMPLEX
}
