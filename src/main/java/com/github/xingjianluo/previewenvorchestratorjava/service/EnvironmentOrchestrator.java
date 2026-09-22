package com.github.xingjianluo.previewenvorchestratorjava.service;

import com.github.xingjianluo.previewenvorchestratorjava.backend.BackendException;
import com.github.xingjianluo.previewenvorchestratorjava.backend.RuntimeBackend;
import com.github.xingjianluo.previewenvorchestratorjava.domain.CreateEnvironmentRequest;
import com.github.xingjianluo.previewenvorchestratorjava.domain.EnvState;
import com.github.xingjianluo.previewenvorchestratorjava.domain.PreviewEnvironment;
import com.github.xingjianluo.previewenvorchestratorjava.domain.TransitionReason;
import com.github.xingjianluo.previewenvorchestratorjava.error.ErrorCode;
import com.github.xingjianluo.previewenvorchestratorjava.error.OrchestratorException;
import com.github.xingjianluo.previewenvorchestratorjava.idem.IdempotencyOutcome;
import com.github.xingjianluo.previewenvorchestratorjava.idem.IdempotencyStore;
import com.github.xingjianluo.previewenvorchestratorjava.lease.LeasePolicy;
import com.github.xingjianluo.previewenvorchestratorjava.quota.QuotaService;
import com.github.xingjianluo.previewenvorchestratorjava.store.EnvironmentRepository;
import com.github.xingjianluo.previewenvorchestratorjava.time.TimeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Control-plane facade enforcing the lifecycle state machine, tenant
 * isolation, quota and idempotency on top of a pluggable
 * {@link RuntimeBackend}.
 *
 * <p>Concurrency model:
 * <ul>
 *   <li>a striped set of per-environment locks serializes transitions of the
 *       same environment (no state overwrite), while different environments
 *       proceed in parallel;</li>
 *   <li>quota reservation is an atomic CAS in {@link QuotaService} (no
 *       oversell);</li>
 *   <li>duplicate submissions are coalesced in {@link IdempotencyStore}
 *       (no duplicate instances).</li>
 * </ul>
 */
@Component
public class EnvironmentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentOrchestrator.class);

    private final EnvironmentRepository repository;
    private final RuntimeBackend backend;
    private final QuotaService quotaService;
    private final IdempotencyStore idempotencyStore;
    private final LeasePolicy leasePolicy;
    private final TimeSource timeSource;

    private final ConcurrentMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public EnvironmentOrchestrator(EnvironmentRepository repository,
                                   RuntimeBackend backend,
                                   QuotaService quotaService,
                                   IdempotencyStore idempotencyStore,
                                   LeasePolicy leasePolicy,
                                   TimeSource timeSource) {
        this.repository = repository;
        this.backend = backend;
        this.quotaService = quotaService;
        this.idempotencyStore = idempotencyStore;
        this.leasePolicy = leasePolicy;
        this.timeSource = timeSource;
    }

    private ReentrantLock lockFor(String envId) {
        return locks.computeIfAbsent(envId, k -> new ReentrantLock());
    }

    // ------------------------------------------------------------------ create

    public PreviewEnvironment createEnvironment(String tenantId, String idempotencyKey,
                                                CreateEnvironmentRequest request) {
        validateTenant(tenantId);
        validateIdempotencyKey(idempotencyKey);
        if (request == null || request.image() == null || request.image().isBlank()) {
            throw new OrchestratorException(ErrorCode.INVALID_REQUEST,
                    "image must not be blank");
        }
        // Deterministic id from the idempotency key: retries find the same
        // instance instead of creating a second one.
        String envId = "env-" + UUID.nameUUIDFromBytes(
                (tenantId + "|" + idempotencyKey).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String scope = "create:" + tenantId;
        IdempotencyOutcome outcome = idempotencyStore.execute(scope, idempotencyKey, () ->
                definitiveOrTransient(() -> doCreate(tenantId, envId, request)));
        return resolveOutcome(scope, idempotencyKey, outcome);
    }

    private IdempotencyOutcome doCreate(String tenantId, String envId,
                                        CreateEnvironmentRequest request) {
        ReentrantLock lock = lockFor(envId);
        lock.lock();
        try {
            PreviewEnvironment existing = repository.findById(envId).orElse(null);
            if (existing != null) {
                if (!existing.getTenantId().equals(tenantId)) {
                    throw new OrchestratorException(ErrorCode.TENANT_MISMATCH,
                            "environment belongs to another tenant");
                }
                EnvState s = existing.getState();
                if (s != EnvState.CREATING && s != EnvState.FAILED) {
                    return IdempotencyOutcome.ok(envId);
                }
                // Retry path: the slot is still reserved (release happens only
                // on TERMINATED), so we must NOT reserve a second time.
            } else {
                // First attempt: reserve capacity before any backend work.
                quotaService.reserve(tenantId);
            }

            PreviewEnvironment env = existing;
            long now = timeSource.nowMillis();
            if (env == null) {
                env = new PreviewEnvironment(envId, tenantId, request.image(), now);
                env.markBorn(now);
                repository.save(env);
            }
            logDecision(env, "CREATE", EnvState.CREATING, EnvState.RUNNING,
                    "quota reserved, calling backend provision+start");
            try {
                backend.provision(envId);
                backend.startInstance(envId);
            } catch (BackendException e) {
                env.transitionTo(EnvState.FAILED, TransitionReason.CREATE_FAILED,
                        timeSource.nowMillis(), false);
                logDecision(env, "CREATE", EnvState.CREATING, EnvState.FAILED,
                        "backend failure: " + e.getMessage());
                throw new OrchestratorException(ErrorCode.BACKEND_FAILURE,
                        "create failed: " + e.getMessage(), e);
            }
            env.transitionTo(EnvState.RUNNING, TransitionReason.CREATE_REQUESTED,
                    timeSource.nowMillis(), true);
            logDecision(env, "CREATE", EnvState.CREATING, EnvState.RUNNING,
                    "backend provision+start succeeded");
            return IdempotencyOutcome.ok(envId);
        } finally {
            // Quota stays reserved while the env exists (incl. FAILED); it is
            // returned only when the env is finally terminated.
            lock.unlock();
        }
    }

    /**
     * Classify a failure thrown by the protected action:
     * <ul>
     *   <li>{@link ErrorCode#BACKEND_FAILURE} and {@link ErrorCode#QUOTA_EXCEEDED}
     *       are <b>transient</b> — propagate so no outcome is cached and a
     *       retry (possibly after quota frees up) can run;</li>
     *   <li>every other rejection is <b>definitive</b> — cache the failure so
     *       replays observe the same stable error code.</li>
     * </ul>
     */
    private IdempotencyOutcome definitiveOrTransient(
            java.util.function.Supplier<IdempotencyOutcome> action) {
        try {
            return action.get();
        } catch (OrchestratorException e) {
            if (e.getErrorCode() == ErrorCode.BACKEND_FAILURE
                    || e.getErrorCode() == ErrorCode.QUOTA_EXCEEDED) {
                throw e;
            }
            return IdempotencyOutcome.failure(e.getErrorCode().name());
        }
    }

    // ------------------------------------------------------------------ start

    public PreviewEnvironment start(String tenantId, String envId, String idempotencyKey) {
        validateTenant(tenantId);
        validateIdempotencyKey(idempotencyKey);
        String scope = "start:" + envId;
        IdempotencyOutcome outcome = idempotencyStore.execute(scope, idempotencyKey,
                () -> definitiveOrTransient(() -> doMutate(tenantId, envId, Operation.START)));
        return resolveOutcome(scope, idempotencyKey, outcome);
    }

    // ------------------------------------------------------------------- stop

    public PreviewEnvironment stop(String tenantId, String envId, String idempotencyKey) {
        validateTenant(tenantId);
        validateIdempotencyKey(idempotencyKey);
        String scope = "stop:" + envId;
        IdempotencyOutcome outcome = idempotencyStore.execute(scope, idempotencyKey,
                () -> definitiveOrTransient(() -> doMutate(tenantId, envId, Operation.STOP)));
        return resolveOutcome(scope, idempotencyKey, outcome);
    }

    // ---------------------------------------------------------------- destroy

    public PreviewEnvironment destroy(String tenantId, String envId, String idempotencyKey) {
        validateTenant(tenantId);
        validateIdempotencyKey(idempotencyKey);
        String scope = "destroy:" + envId;
        IdempotencyOutcome outcome = idempotencyStore.execute(scope, idempotencyKey,
                () -> definitiveOrTransient(() -> doMutate(tenantId, envId, Operation.DESTROY)));
        return resolveOutcome(scope, idempotencyKey, outcome);
    }

    private enum Operation {START, STOP, DESTROY}

    private IdempotencyOutcome doMutate(String tenantId, String envId, Operation op) {
        ReentrantLock lock = lockFor(envId);
        lock.lock();
        try {
            PreviewEnvironment env = requireEnvOfTenant(tenantId, envId);
            switch (op) {
                case START -> doStart(env);
                case STOP -> doStop(env, TransitionReason.STOP_REQUESTED);
                case DESTROY -> doDestroy(env);
            }
            return IdempotencyOutcome.ok(envId);
        } finally {
            lock.unlock();
        }
    }

    private void doStart(PreviewEnvironment env) {
        EnvState from = env.getState();
        switch (from) {
            case STOPPED, STARTING, FAILED -> {
                assertTransition(env, "START", from, EnvState.STARTING);
                try {
                    backend.startInstance(env.getEnvId());
                } catch (BackendException e) {
                    env.transitionTo(EnvState.FAILED, TransitionReason.START_FAILED,
                            timeSource.nowMillis(), false);
                    logDecision(env, "START", from, EnvState.FAILED,
                            "backend failure: " + e.getMessage());
                    throw new OrchestratorException(ErrorCode.BACKEND_FAILURE,
                            "start failed: " + e.getMessage(), e);
                }
                env.transitionTo(EnvState.RUNNING, TransitionReason.START_REQUESTED,
                        timeSource.nowMillis(), true);
                logDecision(env, "START", from, EnvState.RUNNING, "backend start succeeded");
            }
            // A fresh request to start an already running env is a redundant
            // transition and is rejected; same-key retries never reach here
            // because the idempotency store replays the cached outcome.
            case RUNNING -> reject(env, "START", from,
                    "environment is already RUNNING; duplicate transition");
            default -> reject(env, "START", from,
                    "only STOPPED (or failed/starting) environments can be started");
        }
    }

    private void doStop(PreviewEnvironment env, TransitionReason reason) {
        EnvState from = env.getState();
        boolean lease = reason == TransitionReason.IDLE_STOP_LEASE;
        String op = lease ? "IDLE-STOP" : "STOP";
        switch (from) {
            // FAILED is accepted here for the lease reaper retrying a failed
            // auto-stop (manual stop of a failed env remains a rejection below).
            case RUNNING, FAILED -> {
                if (from == EnvState.FAILED && !lease) {
                    reject(env, op, from, "a FAILED environment cannot be manually stopped");
                    return;
                }
                assertTransition(env, op, from, EnvState.STOPPING);
                try {
                    backend.stopInstance(env.getEnvId());
                } catch (BackendException e) {
                    env.transitionTo(EnvState.FAILED, TransitionReason.STOP_FAILED,
                            timeSource.nowMillis(), false);
                    logDecision(env, op, from, EnvState.FAILED,
                            "backend failure: " + e.getMessage());
                    throw new OrchestratorException(ErrorCode.BACKEND_FAILURE,
                            "stop failed: " + e.getMessage(), e);
                }
                env.transitionTo(EnvState.STOPPED, reason, timeSource.nowMillis(),
                        !lease);
                logDecision(env, op, from, EnvState.STOPPED,
                        lease ? "idle lease expired; auto-stopped"
                              : "backend stop succeeded");
            }
            // Redundant stop from a distinct request is rejected; same-key
            // retries are answered from the idempotency cache.
            case STOPPED, STOPPING -> reject(env, op, from,
                    "environment is already " + from + "; duplicate transition");
            default -> reject(env, op, from, "only RUNNING environments can be stopped");
        }
    }

    private void doDestroy(PreviewEnvironment env) {
        EnvState from = env.getState();
        switch (from) {
            case CREATING, RUNNING, STOPPING, STOPPED, STARTING, DESTROYING, FAILED -> {
                assertTransition(env, "DESTROY", from, EnvState.DESTROYING);
                boolean wasRunning = backend.isRunning(env.getEnvId());
                try {
                    if (wasRunning) {
                        backend.stopInstance(env.getEnvId());
                    }
                    backend.deprovision(env.getEnvId());
                } catch (BackendException e) {
                    env.transitionTo(EnvState.FAILED, TransitionReason.DESTROY_FAILED,
                            timeSource.nowMillis(), false);
                    logDecision(env, "DESTROY", from, EnvState.FAILED,
                            "backend failure, residual resources must be cleaned by retry: "
                                    + e.getMessage());
                    throw new OrchestratorException(ErrorCode.BACKEND_FAILURE,
                            "destroy failed: " + e.getMessage(), e);
                }
                env.transitionTo(EnvState.TERMINATED, TransitionReason.DESTROY_REQUESTED,
                        timeSource.nowMillis(), false);
                // Exactly-once release: only reached after a real successful deprovision.
                quotaService.release(env.getTenantId());
                logDecision(env, "DESTROY", from, EnvState.TERMINATED,
                        "resources deprovisioned, quota slot released (now used="
                                + quotaService.used(env.getTenantId()) + ")");
            }
            case TERMINATED -> reject(env, "DESTROY", from,
                    "environment is already TERMINATED; duplicate transition");
        }
    }

    // ------------------------------------------------------------------- reads

    public Optional<PreviewEnvironment> get(String tenantId, String envId) {
        validateTenant(tenantId);
        return repository.findById(envId)
                .filter(env -> env.getTenantId().equals(tenantId));
    }

    public List<PreviewEnvironment> list(String tenantId) {
        validateTenant(tenantId);
        return repository.findByTenant(tenantId);
    }

    /** Cross-tenant access is reported as not found rather than exposing existence. */
    private PreviewEnvironment requireEnvOfTenant(String tenantId, String envId) {
        PreviewEnvironment env = repository.findById(envId).orElseThrow(() ->
                new OrchestratorException(ErrorCode.ENV_NOT_FOUND,
                        "environment not found: " + envId));
        if (!env.getTenantId().equals(tenantId)) {
            throw new OrchestratorException(ErrorCode.TENANT_MISMATCH,
                    "environment " + envId + " does not belong to tenant " + tenantId);
        }
        return env;
    }

    // ------------------------------------------------------------- lease reaper

    /**
     * Scan all environments and apply exactly one due lease action (idle stop
     * or idle destroy). Returns a human-readable description of the action
     * (for tests/logging) or {@code null} when nothing was due.
     */
    public String reclaimIdleEnvironments(long nowMillis) {
        for (PreviewEnvironment env : repository.findAll()) {
            ReentrantLock lock = lockFor(env.getEnvId());
            lock.lock();
            try {
                EnvState state = env.getState();
                if (leasePolicy.shouldIdleStop(state, env.getLastActivityAt(), nowMillis)) {
                    logDecision(env, "REAPER-SCAN", state, state,
                            "idle for " + (nowMillis - env.getLastActivityAt())
                                    + "ms >= " + leasePolicy.idleStopMillis()
                                    + "ms -> auto-stop due");
                    doStop(env, TransitionReason.IDLE_STOP_LEASE);
                    return "IDLE_STOPPED:" + env.getEnvId();
                }
                if (leasePolicy.shouldIdleDestroy(state, env.getStoppedAt(), nowMillis)) {
                    logDecision(env, "REAPER-SCAN", state, state,
                            "stopped for " + (nowMillis - env.getStoppedAt())
                                    + "ms >= " + leasePolicy.idleDestroyMillis()
                                    + "ms -> auto-destroy due");
                    doLeaseDestroy(env);
                    return "IDLE_DESTROYED:" + env.getEnvId();
                }
                // A previous lease-destroy attempt failed mid-flight and left a
                // residual resource: retry it on every subsequent scan so the
                // environment cannot be orphaned.
                if (state == EnvState.FAILED
                        && env.lastReason() == TransitionReason.DESTROY_FAILED
                        && backend.isProvisioned(env.getEnvId())) {
                    logDecision(env, "REAPER-SCAN", state, state,
                            "residual resource from failed lease destroy -> retry cleanup");
                    doLeaseDestroy(env);
                    return "IDLE_DESTROYED:" + env.getEnvId();
                }
                // A previous lease-stop attempt failed while the process is
                // still running: retry the auto-stop on the next scan.
                if (state == EnvState.FAILED
                        && env.lastReason() == TransitionReason.STOP_FAILED
                        && backend.isRunning(env.getEnvId())) {
                    logDecision(env, "REAPER-SCAN", state, state,
                            "process still running after failed lease stop -> retry");
                    doStop(env, TransitionReason.IDLE_STOP_LEASE);
                    return "IDLE_STOPPED:" + env.getEnvId();
                }
            } finally {
                lock.unlock();
            }
        }
        return null;
    }

    private void doLeaseDestroy(PreviewEnvironment env) {
        EnvState from = env.getState();
        assertTransition(env, "IDLE-DESTROY", from, EnvState.DESTROYING);
        try {
            backend.deprovision(env.getEnvId());
        } catch (BackendException e) {
            env.transitionTo(EnvState.FAILED, TransitionReason.DESTROY_FAILED,
                    timeSource.nowMillis(), false);
            logDecision(env, "IDLE-DESTROY", from, EnvState.FAILED,
                    "backend failure; residual cleanup will be retried on next scan: "
                            + e.getMessage());
            throw new OrchestratorException(ErrorCode.BACKEND_FAILURE,
                    "idle destroy failed: " + e.getMessage(), e);
        }
        env.transitionTo(EnvState.TERMINATED, TransitionReason.IDLE_DESTROY_LEASE,
                timeSource.nowMillis(), false);
        quotaService.release(env.getTenantId());
        logDecision(env, "IDLE-DESTROY", from, EnvState.TERMINATED,
                "idle lease expired; auto-destroyed and quota released");
    }

    // --------------------------------------------------------------- internals

    private void assertTransition(PreviewEnvironment env, String op, EnvState from,
                                  EnvState intermediate) {
        logDecision(env, op, from, intermediate, "transition accepted by state machine");
        env.transitionTo(intermediate,
                switch (op) {
                    case "START" -> TransitionReason.START_REQUESTED;
                    case "STOP" -> TransitionReason.STOP_REQUESTED;
                    case "DESTROY" -> TransitionReason.DESTROY_REQUESTED;
                    case "IDLE-STOP" -> TransitionReason.IDLE_STOP_LEASE;
                    case "IDLE-DESTROY" -> TransitionReason.IDLE_DESTROY_LEASE;
                    default -> throw new IllegalStateException("unknown op " + op);
                },
                timeSource.nowMillis(), false);
    }

    private void reject(PreviewEnvironment env, String op, EnvState from, String rationale) {
        logDecision(env, op, from, from, "REJECTED: " + rationale);
        throw new OrchestratorException(ErrorCode.INVALID_STATE_TRANSITION,
                "illegal transition: " + op + " not allowed in state " + from
                        + " (" + rationale + ")");
    }

    private void logDecision(PreviewEnvironment env, String op, EnvState from, EnvState to,
                             String basis) {
        log.info("requestId={} op={} tenant={} env={} {} -> {} basis=[{}]",
                MDC.get("requestId"), op, env.getTenantId(), env.getEnvId(), from, to, basis);
    }

    private PreviewEnvironment resolveOutcome(String scope, String idempotencyKey,
                                              IdempotencyOutcome outcome) {
        if (outcome.success()) {
            return repository.findById(outcome.resourceId())
                    .orElseThrow(() -> new IllegalStateException(
                            "stored success outcome missing resource "
                                    + outcome.resourceId()));
        }
        ErrorCode code = ErrorCode.valueOf(outcome.errorCode());
        throw new OrchestratorException(code,
                "operation previously rejected under idempotency key " + idempotencyKey
                        + " (" + code + ")");
    }

    private void validateTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new OrchestratorException(ErrorCode.INVALID_REQUEST,
                    "tenantId must not be blank");
        }
    }

    private void validateIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new OrchestratorException(ErrorCode.INVALID_REQUEST,
                    "Idempotency-Key header is required");
        }
    }
}
