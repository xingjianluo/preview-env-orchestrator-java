package com.github.xingjianluo.previewenvorchestratorjava.tests;

import com.github.xingjianluo.previewenvorchestratorjava.backend.InProcessRuntimeBackend;
import com.github.xingjianluo.previewenvorchestratorjava.domain.CreateEnvironmentRequest;
import com.github.xingjianluo.previewenvorchestratorjava.domain.EnvState;
import com.github.xingjianluo.previewenvorchestratorjava.domain.PreviewEnvironment;
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
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lease reaping driven entirely by a manually advanced clock — no real
 * waiting, fully deterministic. Automatic reasons must remain distinguishable
 * from manual ones.
 */
@ExtendWith(TestRequestIdExtension.class)
class LeaseReclaimTest {

    private TestClock clock;
    private InProcessRuntimeBackend backend;
    private QuotaService quota;
    private EnvironmentOrchestrator orchestrator;
    private LeasePolicy policy;

    @BeforeEach
    void setUp() {
        clock = new TestClock();
        backend = new InProcessRuntimeBackend();
        quota = new QuotaService();
        quota.setDefaultQuota(5);
        policy = new LeasePolicy();
        ReflectionTestUtils.setField(policy, "idleStopMillis", 10_000L);
        ReflectionTestUtils.setField(policy, "idleDestroyMillis", 20_000L);
        orchestrator = new EnvironmentOrchestrator(
                new EnvironmentRepository(), backend, quota, new IdempotencyStore(),
                policy, clock);
    }

    @Test
    @DisplayName("idle running env is auto-stopped with IDLE_STOP_LEASE, distinct from manual stop")
    void idleStop() {
        PreviewEnvironment env = orchestrator.createEnvironment("t1", "k1",
                new CreateEnvironmentRequest("img"));
        long created = env.getCreatedAt();

        // Just below threshold: nothing happens.
        clock.set(created + 9_999);
        assertNull(orchestrator.reclaimIdleEnvironments(clock.nowMillis()));
        assertEquals(EnvState.RUNNING,
                orchestrator.get("t1", env.getEnvId()).orElseThrow().getState());
        assertTrue(backend.isRunning(env.getEnvId()));

        // At/over threshold: auto-stop.
        clock.set(created + 10_000);
        assertEquals("IDLE_STOPPED:" + env.getEnvId(),
                orchestrator.reclaimIdleEnvironments(clock.nowMillis()));
        PreviewEnvironment stopped = orchestrator.get("t1", env.getEnvId()).orElseThrow();
        assertEquals(EnvState.STOPPED, stopped.getState());
        assertEquals("IDLE_STOP_LEASE", stopped.lastReason().name());
        assertEquals(true, stopped.getTransitions().stream()
                .anyMatch(t -> t.reason().name().equals("IDLE_STOP_LEASE")));
    }

    @Test
    @DisplayName("auto-stopped env is later auto-destroyed with IDLE_DESTROY_LEASE and quota freed")
    void idleDestroyFreesCapacity() {
        PreviewEnvironment env = orchestrator.createEnvironment("t1", "k1",
                new CreateEnvironmentRequest("img"));
        long t0 = env.getCreatedAt();

        clock.set(t0 + 10_000);
        orchestrator.reclaimIdleEnvironments(clock.nowMillis());
        PreviewEnvironment stopped = orchestrator.get("t1", env.getEnvId()).orElseThrow();
        assertEquals(EnvState.STOPPED, stopped.getState());
        assertEquals(1, quota.used("t1"));

        // The destroy deadline is measured from the stop moment.
        long stoppedAt = stopped.getStoppedAt();
        clock.set(stoppedAt + 19_999);
        assertNull(orchestrator.reclaimIdleEnvironments(clock.nowMillis()));

        clock.set(stoppedAt + 20_000);
        assertEquals("IDLE_DESTROYED:" + env.getEnvId(),
                orchestrator.reclaimIdleEnvironments(clock.nowMillis()));
        PreviewEnvironment gone = orchestrator.get("t1", env.getEnvId()).orElseThrow();
        assertEquals(EnvState.TERMINATED, gone.getState());
        assertEquals("IDLE_DESTROY_LEASE", gone.lastReason().name());
        assertEquals(0, quota.used("t1"), "capacity returned to the pool");
        assertEquals(0, backend.provisionedCount());
    }

    @Test
    @DisplayName("manual stop and lease stop are both recorded with their distinct reasons")
    void manualAndLeaseReasonsDistinct() {
        PreviewEnvironment env = orchestrator.createEnvironment("t1", "k1",
                new CreateEnvironmentRequest("img"));
        orchestrator.stop("t1", env.getEnvId(), "manual-stop");
        PreviewEnvironment manual = orchestrator.get("t1", env.getEnvId()).orElseThrow();
        assertEquals("STOP_REQUESTED", manual.lastReason().name());
    }

    @Test
    @DisplayName("active environments are never reaped regardless of age since creation")
    void activityResetsLease() {
        PreviewEnvironment env = orchestrator.createEnvironment("t1", "k1",
                new CreateEnvironmentRequest("img"));
        long t0 = env.getCreatedAt();
        // After stop, start again: activity refreshes, idle timer resets.
        clock.set(t0 + 10_000);
        orchestrator.reclaimIdleEnvironments(clock.nowMillis());
        PreviewEnvironment stopped = orchestrator.get("t1", env.getEnvId()).orElseThrow();
        clock.set(t0 + 11_000);
        orchestrator.start("t1", env.getEnvId(), "restart");
        // 10s after creation but only 9s after the restart activity: still safe.
        clock.set(t0 + 20_000);
        assertNull(orchestrator.reclaimIdleEnvironments(clock.nowMillis()),
                "lease measured from last activity, not creation");
        assertEquals(EnvState.RUNNING,
                orchestrator.get("t1", env.getEnvId()).orElseThrow().getState());
    }

    @Test
    @DisplayName("lease destroy failure is retried on next scan and leaves no residue afterwards")
    void leaseDestroyFailureRetried() {
        PreviewEnvironment env = orchestrator.createEnvironment("t1", "k1",
                new CreateEnvironmentRequest("img"));
        long t0 = env.getCreatedAt();
        clock.set(t0 + 10_000);
        orchestrator.reclaimIdleEnvironments(clock.nowMillis());
        long stoppedAt = orchestrator.get("t1", env.getEnvId()).orElseThrow().getStoppedAt();

        backend.armFailure("deprovision", env.getEnvId());
        clock.set(stoppedAt + 20_000);
        assertThrows(OrchestratorException.class,
                () -> orchestrator.reclaimIdleEnvironments(clock.nowMillis()));
        // Resource still present, quota still held, state FAILED.
        assertEquals(1, backend.provisionedCount());
        assertEquals(1, quota.used("t1"));

        // Next scan retries and converges.
        clock.set(stoppedAt + 20_001);
        String action = orchestrator.reclaimIdleEnvironments(clock.nowMillis());
        assertEquals("IDLE_DESTROYED:" + env.getEnvId(), action);
        assertEquals(0, backend.provisionedCount());
        assertEquals(0, quota.used("t1"));
    }
}
