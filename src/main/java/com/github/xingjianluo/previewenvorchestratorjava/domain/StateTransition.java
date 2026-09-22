package com.github.xingjianluo.previewenvorchestratorjava.domain;

/**
 * One immutable entry in an environment's transition history.
 */
public record StateTransition(
        EnvState from,
        EnvState to,
        TransitionReason reason,
        long atMillis
) {
}
