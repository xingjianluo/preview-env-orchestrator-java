package com.github.xingjianluo.previewenvorchestratorjava.tests.orchestrator;

import com.github.xingjianluo.previewenvorchestratorjava.backend.InProcessRuntimeBackend;
import com.github.xingjianluo.previewenvorchestratorjava.config.OrchestratorProperties;
import com.github.xingjianluo.previewenvorchestratorjava.domain.ReceiptStatus;
import com.github.xingjianluo.previewenvorchestratorjava.receipt.RequestReceipt;
import com.github.xingjianluo.previewenvorchestratorjava.service.EnvironmentService;
import com.github.xingjianluo.previewenvorchestratorjava.service.OperationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同一请求标识并发提交：执行权唯一，其他提交立即得到 PROCESSING，回查最终稳定成功。
 */
class ReceiptConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(ReceiptConcurrencyTest.class);

    static class SlowBackend extends InProcessRuntimeBackend {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public String provision(String envId, String tenantId) {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return super.provision(envId, tenantId);
        }
    }

    private SlowBackend backend;
    private EnvironmentService service;

    @BeforeEach
    void setUp() {
        backend = new SlowBackend();
        OrchestratorProperties props = new OrchestratorProperties(
                8, Duration.ofMinutes(10), Duration.ofMinutes(30));
        service = new EnvironmentService(backend, new MutableClock(Instant.parse("2026-09-22T00:00:00Z")), props);
    }

    @Test
    void concurrentSameRequestIdExecutesOnceOthersSeeProcessingThenStableSuccess() throws Exception {
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<OperationResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return service.create("tenant-a", "hot-key", "hot-request");
            }));
        }
        ready.await();
        go.countDown();

        // 等唯一执行者进入开通，此时其余并发提交应拿到 PROCESSING（非阻塞）
        assertTrue(backend.entered.await(5, TimeUnit.SECONDS));
        RequestReceipt during = service.getReceipt("tenant-a", "hot-request");
        assertEquals(ReceiptStatus.PROCESSING, during.status());

        backend.release.countDown();

        int succeeded = 0;
        int processing = 0;
        String envId = null;
        for (Future<OperationResult> f : futures) {
            OperationResult r = f.get(10, TimeUnit.SECONDS);
            // 在途窗口内提交的得到 PROCESSING；若执行者已抢先结束，则直接看到 SUCCEEDED；
            // 两种时序下都绝不能产生第二个执行结果
            if (r.receipt().status() == ReceiptStatus.SUCCEEDED) {
                succeeded++;
                envId = r.receipt().envId();
            } else if (r.receipt().status() == ReceiptStatus.PROCESSING) {
                processing++;
            }
        }
        pool.shutdownNow();

        assertTrue(succeeded >= 1, "至少有一个提交拿到成功结论");
        assertEquals(succeeded + processing, threads, "其余提交要么处理中要么已见成功，无异常结论");
        assertEquals(1, backend.provisionCalls(), "无论线程如何交错，后端开通只发生一次");
        assertEquals(1, service.quotaUsed("tenant-a"), "无论线程如何交错，只占一份配额");
        assertEquals(1, service.list("tenant-a").size(), "只能存在一个环境");

        // 所有提交随后用同一标识回查，结论都稳定为成功且指向同一环境
        for (int i = 0; i < 3; i++) {
            RequestReceipt settled = service.getReceipt("tenant-a", "hot-request");
            assertEquals(ReceiptStatus.SUCCEEDED, settled.status());
            assertEquals(envId, settled.envId());
        }
        log.info("concurrent same requestId: threads={} executed=1 processing={} env={}",
                threads, processing, envId);
    }
}
