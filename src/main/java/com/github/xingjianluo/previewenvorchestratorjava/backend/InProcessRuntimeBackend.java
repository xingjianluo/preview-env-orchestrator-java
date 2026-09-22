package com.github.xingjianluo.previewenvorchestratorjava.backend;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory backend simulating environment instances with two independent
 * resource layers:
 * <ul>
 *     <li><b>provisioned</b> &mdash; backing resources allocated;</li>
 *     <li><b>running</b> &mdash; the instance process running (subset of provisioned).</li>
 * </ul>
 *
 * <p>All operations are idempotent where a retry is meaningful (starting an
 * already running instance, stopping a stopped one), which lets the
 * orchestrator safely retry after a mid-flight failure. Failure injection is
 * per environment id and per operation, and deliberately simulates
 * "half-done" work: e.g. {@code provision} may mark resources allocated and
 * then fail, leaving a residual resource that callers must clean up.
 */
@Component
public class InProcessRuntimeBackend implements RuntimeBackend {

    private final java.util.Set<String> provisioned = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> running = ConcurrentHashMap.newKeySet();

    /** Armed failures: operation name -> set of env ids that must fail. */
    private final ConcurrentMap<String, java.util.Set<String>> armedFailures = new ConcurrentHashMap<>();

    private static final String PROVISION = "provision";
    private static final String START = "start";
    private static final String STOP = "stop";
    private static final String DEPROVISION = "deprovision";

    /** Make the next invocation of {@code operation} for {@code envId} fail. */
    public void armFailure(String operation, String envId) {
        armedFailures.computeIfAbsent(operation, k -> ConcurrentHashMap.newKeySet()).add(envId);
    }

    /** Failure fires once, then is automatically disarmed (simulates a transient fault). */
    private boolean consumeFailure(String operation, String envId) {
        java.util.Set<String> ids = armedFailures.get(operation);
        return ids != null && ids.remove(envId);
    }

    public void clearFailures() {
        armedFailures.clear();
    }

    @Override
    public void provision(String envId) {
        if (consumeFailure(PROVISION, envId)) {
            // Simulate partial allocation before the fault: residual resources exist.
            provisioned.add(envId);
            throw new BackendException("provision failed for " + envId
                    + " (partial allocation left behind)");
        }
        provisioned.add(envId);
    }

    @Override
    public void startInstance(String envId) {
        if (!provisioned.contains(envId)) {
            throw new BackendException("cannot start unprovisioned instance " + envId);
        }
        if (consumeFailure(START, envId)) {
            throw new BackendException("start failed for " + envId);
        }
        running.add(envId);
    }

    @Override
    public void stopInstance(String envId) {
        if (consumeFailure(STOP, envId)) {
            throw new BackendException("stop failed for " + envId);
        }
        running.remove(envId);
    }

    @Override
    public void deprovision(String envId) {
        if (consumeFailure(DEPROVISION, envId)) {
            // Resource intentionally remains allocated so the caller must retry.
            throw new BackendException("deprovision failed for " + envId
                    + " (resource still allocated)");
        }
        running.remove(envId);
        provisioned.remove(envId);
    }

    @Override
    public boolean isProvisioned(String envId) {
        return provisioned.contains(envId);
    }

    @Override
    public boolean isRunning(String envId) {
        return running.contains(envId);
    }

    /** Test/verification helper: total resources currently held by the backend. */
    public int provisionedCount() {
        return provisioned.size();
    }
}
