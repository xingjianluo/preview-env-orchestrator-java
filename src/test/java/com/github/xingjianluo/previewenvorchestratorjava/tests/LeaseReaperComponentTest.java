package com.github.xingjianluo.previewenvorchestratorjava.tests;

import com.github.xingjianluo.previewenvorchestratorjava.backend.InProcessRuntimeBackend;
import com.github.xingjianluo.previewenvorchestratorjava.domain.CreateEnvironmentRequest;
import com.github.xingjianluo.previewenvorchestratorjava.domain.EnvState;
import com.github.xingjianluo.previewenvorchestratorjava.idem.IdempotencyStore;
import com.github.xingjianluo.previewenvorchestratorjava.lease.LeasePolicy;
import com.github.xingjianluo.previewenvorchestratorjava.lease.LeaseReaper;
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

@ExtendWith(TestRequestIdExtension.class)
class LeaseReaperComponentTest {

    private TestClock clock;
    private InProcessRuntimeBackend backend;
    private EnvironmentOrchestrator orchestrator;
    private LeaseReaper reaper;

    @BeforeEach
    void setUp() {
        clock = new TestClock();
        backend = new InProcessRuntimeBackend();
        QuotaService quota = new QuotaService();
        quota.setDefaultQuota(5);
        LeasePolicy policy = new LeasePolicy();
        ReflectionTestUtils.setField(policy, "idleStopMillis", 100L);
        ReflectionTestUtils.setField(policy, "idleDestroyMillis", 200L);
        orchestrator = new EnvironmentOrchestrator(
                new EnvironmentRepository(), backend, quota, new IdempotencyStore(),
                policy, clock);
        reaper = new LeaseReaper(orchestrator, clock);
    }

    @Test
    @DisplayName("tick does nothing when nothing is due")
    void tickEmpty() {
        assertNull(reaper.tick());
    }

    @Test
    @DisplayName("tick auto-stops due environments and reports the action")
    void tickReaps() {
        var env = orchestrator.createEnvironment("t1", "k1",
                new CreateEnvironmentRequest("img"));
        clock.advance(100);
        assertEquals("IDLE_STOPPED:" + env.getEnvId(), reaper.tick());
        assertEquals(EnvState.STOPPED,
                orchestrator.get("t1", env.getEnvId()).orElseThrow().getState());
    }

    @Test
    @DisplayName("backend failure during tick returns RETRY instead of throwing")
    void tickFailureIsRetryable() {
        var env = orchestrator.createEnvironment("t1", "k1",
                new CreateEnvironmentRequest("img"));
        clock.advance(100);
        backend.armFailure("stop", env.getEnvId());
        assertEquals("RETRY", reaper.tick());
        // Next tick (failure was one-shot) succeeds.
        clock.advance(1);
        assertEquals("IDLE_STOPPED:" + env.getEnvId(), reaper.tick());
    }
}
