package com.github.xingjianluo.previewenvorchestratorjava.backend;

/**
 * Replaceable in-process runtime backend. The orchestration control plane
 * depends only on this interface; tests substitute an in-process
 * implementation (optionally injecting failures) instead of real machines.
 */
public interface RuntimeBackend {

    /** Allocate backing resources for the environment. */
    void provision(String envId);

    /** Start the instance's process. */
    void startInstance(String envId);

    /** Stop the instance's process; backing resources remain allocated. */
    void stopInstance(String envId);

    /** Release all backing resources. */
    void deprovision(String envId);

    /** Whether backing resources are currently allocated. */
    boolean isProvisioned(String envId);

    /** Whether the instance process is running. */
    boolean isRunning(String envId);
}
