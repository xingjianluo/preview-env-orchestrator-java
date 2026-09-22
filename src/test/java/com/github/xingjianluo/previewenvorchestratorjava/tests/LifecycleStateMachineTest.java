package com.github.xingjianluo.previewenvorchestratorjava.tests;

import com.github.xingjianluo.previewenvorchestratorjava.backend.InProcessRuntimeBackend;
import com.github.xingjianluo.previewenvorchestratorjava.domain.CreateEnvironmentRequest;
import com.github.xingjianluo.previewenvorchestratorjava.domain.EnvState;
import com.github.xingjianluo.previewenvorchestratorjava.domain.PreviewEnvironment;
import com.github.xingjianluo.previewenvorchestratorjava.error.ErrorCode;
import com.github.xingjianluo.previewenvorchestratorjava.error.OrchestratorException;
import com.github.xingjianluo.previewenvorchestratorjava.idem.IdempotencyStore;
import com.github.xingjianluo.previewenvorchestratorjava.lease.LeasePolicy;
import com.github.xingjianluo.previewenvorchestratorjava.quota.QuotaService;
import com.github.xingjianluo.previewenvorchestratorjava.service.EnvironmentOrchestrator;
import com.github.xingjianluo.previewenvorchestratorjava.store.EnvironmentRepository;
import com.github.xingjianluo.previewenvorchestratorjava.tests.support.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.github.xingjianluo.previewenvorchestratorjava.tests.support.TestRequestIdExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lifecycle happy path and illegal-transition rejection. Decision logging is
 * emitted by the orchestrator (requestId, before/after state, basis).
 */
@ExtendWith(TestRequestIdExtension.class)
class LifecycleStateMachineTest {

    private TestClock clock;
    private InProcessRuntimeBackend backend;
    private QuotaService quota;
    private EnvironmentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        clock = new TestClock();
        backend = new InProcessRuntimeBackend();
        quota = new QuotaService();
        quota.setDefaultQuota(3);
        orchestrator = new EnvironmentOrchestrator(
                new EnvironmentRepository(), backend, quota, new IdempotencyStore(),
                new LeasePolicy(), clock);
    }

    @Test
    @DisplayName("full lifecycle: create -> running -> stopped -> running -> terminated")
    void fullLifecycle() {
        PreviewEnvironment env = orchestrator.createEnvironment("t1", "k1",
                new CreateEnvironmentRequest("img:1"));
        assertEquals(EnvState.RUNNING, env.getState());
        assertTrue(backend.isRunning(env.getEnvId()));

        env = orchestrator.stop("t1", env.getEnvId(), "k2");
        assertEquals(EnvState.STOPPED, env.getState());
        assertEquals("STOP_REQUESTED", env.lastReason().name());

        // Same-key replay: identical outcome, no second transition recorded.
        int transitionsBefore = env.getTransitions().size();
        PreviewEnvironment replayed = orchestrator.stop("t1", env.getEnvId(), "k2");
        assertEquals(EnvState.STOPPED, replayed.getState());
        assertEquals(transitionsBefore, replayed.getTransitions().size());

        // A fresh key repeating the same redundant transition is rejected.
        PreviewEnvironment stopped = env;
        OrchestratorException dup = assertThrows(OrchestratorException.class,
                () -> orchestrator.stop("t1", stopped.getEnvId(), "k2b"));
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, dup.getErrorCode());

        env = orchestrator.start("t1", env.getEnvId(), "k3");
        assertEquals(EnvState.RUNNING, env.getState());

        env = orchestrator.destroy("t1", env.getEnvId(), "k4");
        assertEquals(EnvState.TERMINATED, env.getState());
        assertTrue(!backend.isProvisioned(env.getEnvId()));
        assertEquals(0, quota.used("t1"));
    }

    @Test
    @DisplayName("illegal transitions are rejected with INVALID_STATE_TRANSITION")
    void illegalTransitions() {
        PreviewEnvironment env = orchestrator.createEnvironment("t1", "k1",
                new CreateEnvironmentRequest("img"));

        // RUNNING cannot be started.
        OrchestratorException ex = assertThrows(OrchestratorException.class,
                () -> orchestrator.start("t1", env.getEnvId(), "sk1"));
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, ex.getErrorCode());

        // stop then destroy, and operations on TERMINATED are rejected/noop accordingly.
        orchestrator.stop("t1", env.getEnvId(), "sk2");
        orchestrator.destroy("t1", env.getEnvId(), "sk3");

        // Starting a terminated environment is illegal.
        OrchestratorException ex2 = assertThrows(OrchestratorException.class,
                () -> orchestrator.start("t1", env.getEnvId(), "sk4"));
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, ex2.getErrorCode());
    }

    @Test
    @DisplayName("cross-tenant access is rejected")
    void tenantIsolation() {
        PreviewEnvironment env = orchestrator.createEnvironment("t1", "k1",
                new CreateEnvironmentRequest("img"));
        OrchestratorException ex = assertThrows(OrchestratorException.class,
                () -> orchestrator.stop("t2", env.getEnvId(), "k9"));
        assertEquals(ErrorCode.TENANT_MISMATCH, ex.getErrorCode());
        assertTrue(orchestrator.get("t2", env.getEnvId()).isEmpty());
    }

    @Test
    @DisplayName("missing idempotency key is an INVALID_REQUEST")
    void missingKey() {
        OrchestratorException ex = assertThrows(OrchestratorException.class,
                () -> orchestrator.createEnvironment("t1", "  ",
                        new CreateEnvironmentRequest("img")));
        assertEquals(ErrorCode.INVALID_REQUEST, ex.getErrorCode());
    }
}
