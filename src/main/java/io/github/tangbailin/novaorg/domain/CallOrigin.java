package io.github.tangbailin.novaorg.domain;

/**
 * Where a tool invocation came from. This distinction matters during an audit:
 * a call the model asked for is judged one way, a call the platform injected for grounding
 * another - and reviewers must be able to tell them apart.
 */
public enum CallOrigin {
    MODEL,
    POLICY
}
