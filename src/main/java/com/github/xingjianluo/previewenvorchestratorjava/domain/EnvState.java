package com.github.xingjianluo.previewenvorchestratorjava.domain;

/**
 * Lifecycle states of a preview environment.
 */
public enum EnvState {
    CREATING,
    RUNNING,
    STOPPING,
    STOPPED,
    STARTING,
    DESTROYING,
    TERMINATED,
    FAILED
}
