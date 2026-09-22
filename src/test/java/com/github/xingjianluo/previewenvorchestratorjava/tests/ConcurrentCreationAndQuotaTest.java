package com.github.xingjianluo.previewenvorchestratorjava.tests;

import com.github.xingjianluo.previewenvorchestratorjava.backend.InProcessRuntimeBackend;
import com.github.xingjianluo.previewenvorchestratorjava.domain.CreateEnvironmentRequest;
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
import org.slf4j.MDC;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.github.xingjianluo.previewenvorchestratorjava.tests.support.TestRequestIdExtension;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency guarantees:
 * <ul>
 *   <li>parallel duplicate submissions with the same idempotency key create
 *       exactly one instance;</li>
 *   <li>parallel distinct creates never exceed the tenant quota (no
 *       oversell);</li>
 *   <li>parallel competing transitions on one environment never corrupt state.</li>
 * </ul>
 */
@ExtendWith(TestRequestIdExtension.class)
class ConcurrentCreationAndQuotaTest {

    private TestClock clock;
    private InProcessRuntimeBackend backend;
    private QuotaService quota;
    private EnvironmentRepository repository;
    private EnvironmentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        clock = new TestClock();
        backend = new InProcessRuntimeBackend();
        quota = new QuotaService();
        quota.setDefaultQuota(2);
        repository = new EnvironmentRepository();
        orchestrator = new EnvironmentOrchestrator(
                repository, backend, quota, new IdempotencyStore(),
                new LeasePolicy(), clock);
    }

    @Test
    @DisplayName("50 parallel duplicate create requests create exactly one environment")
    void concurrentDuplicateCreate() throws Exception {
        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> createdIds = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        try {
            var futures = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    MDC.put("requestId", "dup-worker-" + idx);
                    try {
                        start.await();
                        PreviewEnvironment e = orchestrator.createEnvironment(
                                "t1", "dup-key", new CreateEnvironmentRequest("img"));
                        createdIds.add(e.getEnvId());
                    } catch (OrchestratorException ex) {
                        errors.add(ex);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        errors.add(ie);
                    }
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertTrue(errors.isEmpty(), "no request should fail: " + errors);
        assertEquals(threads, createdIds.size(), "all requests return an environment");
        assertEquals(1, createdIds.stream().distinct().count(),
                "every duplicate resolves to the single canonical instance");
        assertEquals(1, repository.findAll().size(), "only one instance stored");
        assertEquals(1, quota.used("t1"), "only one quota slot consumed");
        assertEquals(1, backend.provisionedCount(), "backend allocated resources once");
    }

    @Test
    @DisplayName("parallel distinct creates never oversell the quota")
    void quotaNeverOversoldUnderContention() throws Exception {
        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> successes = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<ErrorCode> rejectionCodes = new ConcurrentLinkedQueue<>();
        try {
            var futures = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < threads; i++) {
                String key = "key-" + i;
                final int idx = i;
                futures.add(pool.submit(() -> {
                    MDC.put("requestId", "quota-worker-" + idx);
                    try {
                        start.await();
                        PreviewEnvironment e = orchestrator.createEnvironment(
                                "t1", key, new CreateEnvironmentRequest("img"));
                        successes.add(e.getEnvId());
                    } catch (OrchestratorException ex) {
                        rejectionCodes.add(ex.getErrorCode());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(2, successes.size(), "exactly quota-many creates succeed");
        assertEquals(threads - 2, rejectionCodes.size(), "all others are rejected");
        assertTrue(rejectionCodes.stream().allMatch(c -> c == ErrorCode.QUOTA_EXCEEDED),
                "every rejection is specifically QUOTA_EXCEEDED: " + rejectionCodes);
        assertEquals(2, quota.used("t1"));
        assertEquals(2, backend.provisionedCount());
    }

    @Test
    @DisplayName("destroy returns capacity so a blocked create can later succeed")
    void quotaReturnedAfterDestroy() {
        PreviewEnvironment a = orchestrator.createEnvironment("t1", "a",
                new CreateEnvironmentRequest("img"));
        orchestrator.createEnvironment("t1", "b", new CreateEnvironmentRequest("img"));
        OrchestratorException ex = expectQuotaFailure(() ->
                orchestrator.createEnvironment("t1", "c", new CreateEnvironmentRequest("img")));
        assertNotNull(ex);

        orchestrator.destroy("t1", a.getEnvId(), "da");
        assertEquals(1, quota.used("t1"));

        PreviewEnvironment c = orchestrator.createEnvironment("t1", "c",
                new CreateEnvironmentRequest("img"));
        assertNotNull(c);
        assertEquals(2, quota.used("t1"));
    }

    @Test
    @DisplayName("competing start/stop requests on one env never produce contradictory state")
    void competingTransitions() throws Exception {
        PreviewEnvironment env = orchestrator.createEnvironment("t1", "k1",
                new CreateEnvironmentRequest("img"));
        // Stop once so starts are legal again.
        orchestrator.stop("t1", env.getEnvId(), "s1");

        int rounds = 40;
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<ErrorCode> codes = new ConcurrentLinkedQueue<>();
        try {
            var futures = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < rounds; i++) {
                boolean doStart = (i % 2 == 0);
                String key = "k-" + i;
                final int idx = i;
                futures.add(pool.submit(() -> {
                    MDC.put("requestId", "transition-worker-" + idx);
                    try {
                        start.await();
                        if (doStart) {
                            orchestrator.start("t1", env.getEnvId(), key);
                        } else {
                            orchestrator.stop("t1", env.getEnvId(), key);
                        }
                    } catch (OrchestratorException e) {
                        codes.add(e.getErrorCode());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // Every rejection (if any) must be an explicit lifecycle conflict —
        // never a corrupt/overwritten state or a backend assertion error.
        assertTrue(codes.stream().allMatch(c -> c == ErrorCode.INVALID_STATE_TRANSITION),
                "unexpected failures: " + codes);
        PreviewEnvironment finalEnv = orchestrator.get("t1", env.getEnvId()).orElseThrow();
        assertTrue(
                finalEnv.getState().name().equals("RUNNING")
                        || finalEnv.getState().name().equals("STOPPED"),
                "final state must be consistent, was " + finalEnv.getState());
    }

    private OrchestratorException expectQuotaFailure(Callable<?> callable) {
        try {
            callable.call();
            return null;
        } catch (OrchestratorException e) {
            assertEquals(ErrorCode.QUOTA_EXCEEDED, e.getErrorCode());
            return e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
