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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mid-flight backend failures must be retryable, leave no untracked or
 * double-counted resources, and eventually converge.
 */
@ExtendWith(TestRequestIdExtension.class)
class FailureRetryTest {

    private TestClock clock;
    private InProcessRuntimeBackend backend;
    private QuotaService quota;
    private EnvironmentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        clock = new TestClock();
        backend = new InProcessRuntimeBackend();
        quota = new QuotaService();
        quota.setDefaultQuota(5);
        orchestrator = new EnvironmentOrchestrator(
                new EnvironmentRepository(), backend, quota, new IdempotencyStore(),
                new LeasePolicy(), clock);
    }

    @Test
    @DisplayName("failed create leaves a tracked FAILED env; retry with same key converges to RUNNING")
    void createFailureRetry() {
        // First request: provision partially allocates then fails.
        String envId = "env-" + java.util.UUID.nameUUIDFromBytes("t1|k1".getBytes()).toString();
        backend.armFailure("provision", envId);

        OrchestratorException ex = assertThrows(OrchestratorException.class,
                () -> orchestrator.createEnvironment("t1", "k1",
                        new CreateEnvironmentRequest("img")));
        assertEquals(ErrorCode.BACKEND_FAILURE, ex.getErrorCode());

        // Residual resource still allocated, but it is tracked; quota held once.
        assertTrue(backend.isProvisioned(envId), "partial allocation remains");
        assertEquals(1, quota.used("t1"), "quota counted exactly once");
        PreviewEnvironment failed = orchestrator.get("t1", envId).orElseThrow();
        assertEquals(EnvState.FAILED, failed.getState());
        assertEquals("CREATE_FAILED", failed.lastReason().name());

        // Retry with the same idempotency key: no new reservation, cleanup+converge.
        PreviewEnvironment ok = orchestrator.createEnvironment("t1", "k1",
                new CreateEnvironmentRequest("img"));
        assertEquals(EnvState.RUNNING, ok.getState());
        assertEquals(1, quota.used("t1"), "retry must not double-count quota");
        assertEquals(1, backend.provisionedCount(), "single backend resource");
        assertTrue(backend.isRunning(envId));
    }

    @Test
    @DisplayName("failed destroy leaves resource allocated; retry cleans it and releases quota once")
    void destroyFailureRetry() {
        PreviewEnvironment env = orchestrator.createEnvironment("t1", "k1",
                new CreateEnvironmentRequest("img"));
        assertEquals(1, quota.used("t1"));

        backend.armFailure("deprovision", env.getEnvId());
        OrchestratorException ex = assertThrows(OrchestratorException.class,
                () -> orchestrator.destroy("t1", env.getEnvId(), "d1"));
        assertEquals(ErrorCode.BACKEND_FAILURE, ex.getErrorCode());

        // Residual resource retained, quota NOT released (env not terminal).
        assertTrue(backend.isProvisioned(env.getEnvId()));
        assertEquals(1, quota.used("t1"));
        assertEquals(EnvState.FAILED,
                orchestrator.get("t1", env.getEnvId()).orElseThrow().getState());

        // Retry succeeds.
        PreviewEnvironment gone = orchestrator.destroy("t1", env.getEnvId(), "d1");
        assertEquals(EnvState.TERMINATED, gone.getState());
        assertFalse(backend.isProvisioned(env.getEnvId()));
        assertEquals(0, quota.used("t1"), "quota released exactly once");
        assertEquals(0, backend.provisionedCount());
    }

    @Test
    @DisplayName("definitive rejection is replayed under the same key and never reaches the backend twice")
    void definitiveRejectionCached() {
        PreviewEnvironment env = orchestrator.createEnvironment("t1", "k1",
                new CreateEnvironmentRequest("img"));
        int before = env.getTransitions().size();

        // First illegal start (running env) => cached rejection.
        OrchestratorException first = assertThrows(OrchestratorException.class,
                () -> orchestrator.start("t1", env.getEnvId(), "bad"));
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, first.getErrorCode());

        // Same key replay: same code, no extra transition/audit entry.
        OrchestratorException second = assertThrows(OrchestratorException.class,
                () -> orchestrator.start("t1", env.getEnvId(), "bad"));
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, second.getErrorCode());
        assertEquals(before,
                orchestrator.get("t1", env.getEnvId()).orElseThrow().getTransitions().size(),
                "a rejected transition records no state change; replay hits the cache");
        assertEquals(EnvState.RUNNING,
                orchestrator.get("t1", env.getEnvId()).orElseThrow().getState());
    }
}
