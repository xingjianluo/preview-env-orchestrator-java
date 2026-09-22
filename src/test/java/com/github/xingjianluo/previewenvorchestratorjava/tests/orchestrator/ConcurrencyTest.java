package com.github.xingjianluo.previewenvorchestratorjava.tests.orchestrator;

import com.github.xingjianluo.previewenvorchestratorjava.backend.InProcessRuntimeBackend;
import com.github.xingjianluo.previewenvorchestratorjava.config.OrchestratorProperties;
import com.github.xingjianluo.previewenvorchestratorjava.domain.EnvRecord;
import com.github.xingjianluo.previewenvorchestratorjava.domain.EnvState;
import com.github.xingjianluo.previewenvorchestratorjava.domain.FailureReason;
import com.github.xingjianluo.previewenvorchestratorjava.service.EnvironmentService;
import com.github.xingjianluo.previewenvorchestratorjava.service.OrchestratorException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多租户并发场景：同一环境/同一配额的争用不得产生重复实例、超卖或状态覆盖。
 */
class ConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyTest.class);

    private InProcessRuntimeBackend backend;
    private EnvironmentService service;

    @BeforeEach
    void setUp() {
        backend = new InProcessRuntimeBackend();
        OrchestratorProperties props = new OrchestratorProperties(
                8, Duration.ofMinutes(10), Duration.ofMinutes(30));
        service = new EnvironmentService(backend, new MutableClock(Instant.parse("2026-09-22T00:00:00Z")), props);
    }

    private <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Callable<T>> wrapped = tasks.stream().map(t -> (Callable<T>) () -> {
            ready.countDown();
            go.await();
            return t.call();
        }).toList();
        List<Future<T>> futures = wrapped.stream().map(pool::submit).toList();
        ready.await();
        go.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) {
            results.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        return results;
    }

    @Test
    void concurrentDuplicateSubmitsYieldSingleInstance() throws Exception {
        int threads = 16;
        List<Callable<EnvRecord>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> service.create("tenant-a", "same-key"));
        }
        List<EnvRecord> results = runConcurrently(tasks);
        Set<String> envIds = new HashSet<>();
        results.forEach(r -> envIds.add(r.envId()));
        assertEquals(1, envIds.size(), "并发重复提交只能产生一个环境");
        assertEquals(1, backend.provisionCalls());
        assertEquals(1, service.quotaUsed("tenant-a"));
        assertEquals(1, service.list("tenant-a").size());
        log.info("concurrent duplicate submits: threads={} distinctEnvIds={} provisionCalls={}",
                threads, envIds.size(), backend.provisionCalls());
    }

    @Test
    void concurrentCreatesNeverOversellQuota() throws Exception {
        int threads = 32; // 配额 8，远小于并发数
        AtomicInteger rejected = new AtomicInteger();
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int idx = i;
            tasks.add(() -> {
                try {
                    return service.create("tenant-a", "key-" + idx).envId();
                } catch (OrchestratorException e) {
                    assertEquals(FailureReason.QUOTA_EXCEEDED, e.reason());
                    rejected.incrementAndGet();
                    return null;
                }
            });
        }
        List<String> results = runConcurrently(tasks);
        long created = results.stream().filter(java.util.Objects::nonNull).distinct().count();
        assertEquals(8, created, "成功数必须等于配额，不得超卖");
        assertEquals(threads - 8, rejected.get(), "超出配额的请求必须被拒绝");
        assertEquals(8, service.quotaUsed("tenant-a"));
        assertEquals(8, service.list("tenant-a").size());
        log.info("quota contention: threads={} created={} rejected={}", threads, created, rejected.get());
    }

    @Test
    void concurrentTenantsAreIsolated() throws Exception {
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            String tenant = "tenant-" + t;
            for (int i = 0; i < 8; i++) {
                int idx = i;
                tasks.add(() -> service.create(tenant, "k" + idx).envId() == null ? 0 : 1);
            }
        }
        List<Integer> results = runConcurrently(tasks);
        assertEquals(32, results.stream().mapToInt(Integer::intValue).sum());
        for (int t = 0; t < 4; t++) {
            assertEquals(8, service.list("tenant-" + t).size(), "每租户独立配额互不影响");
        }
    }

    @Test
    void concurrentStartStopOnSameEnvConvergesToConsistentState() throws Exception {
        EnvRecord env = service.create("tenant-a", "flip-key");
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            boolean start = i % 2 == 0;
            tasks.add(() -> {
                try {
                    EnvRecord r = start
                            ? service.start("tenant-a", env.envId())
                            : service.stop("tenant-a", env.envId());
                    return r.state().name();
                } catch (OrchestratorException e) {
                    // 并发下部分迁移因状态已被改变而被拒绝，属预期
                    assertEquals(FailureReason.ILLEGAL_STATE_TRANSITION, e.reason());
                    return "REJECTED";
                }
            });
        }
        runConcurrently(tasks);
        EnvRecord finalState = service.get("tenant-a", env.envId());
        assertTrue(finalState.state() == EnvState.RUNNING || finalState.state() == EnvState.STOPPED,
                "并发启停后状态必须收敛到合法值");
        assertTrue(backend.exists(env.envId()));
        log.info("concurrent start/stop converged: env={} finalState={} version={}",
                env.envId(), finalState.state(), finalState.version());
    }
}
