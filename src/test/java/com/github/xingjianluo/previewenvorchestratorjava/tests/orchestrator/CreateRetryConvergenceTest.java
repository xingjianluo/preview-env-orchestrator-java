package com.github.xingjianluo.previewenvorchestratorjava.tests.orchestrator;

import com.github.xingjianluo.previewenvorchestratorjava.config.OrchestratorProperties;
import com.github.xingjianluo.previewenvorchestratorjava.domain.EnvRecord;
import com.github.xingjianluo.previewenvorchestratorjava.domain.EnvState;
import com.github.xingjianluo.previewenvorchestratorjava.domain.FailureReason;
import com.github.xingjianluo.previewenvorchestratorjava.domain.ReceiptStatus;
import com.github.xingjianluo.previewenvorchestratorjava.receipt.RequestReceipt;
import com.github.xingjianluo.previewenvorchestratorjava.service.EnvironmentService;
import com.github.xingjianluo.previewenvorchestratorjava.service.OperationResult;
import com.github.xingjianluo.previewenvorchestratorjava.service.OrchestratorException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 创建重试与清理的收敛性（CI 用法回归）：
 * 调用方不带 body 里的幂等键、只带自己生成的 X-Request-Id 重试时，
 * 必须合并到同一个环境（只开通一次、只占一份配额），且任何失败/清理路径最终自洽。
 */
class CreateRetryConvergenceTest {

    private static final Logger log = LoggerFactory.getLogger(CreateRetryConvergenceTest.class);
    private static final String TENANT = "tenant-a";

    private PhaseGateTest.BlockingBackend backend;
    private MutableClock clock;
    private EnvironmentService service;

    @BeforeEach
    void setUp() {
        backend = new PhaseGateTest.BlockingBackend();
        clock = new MutableClock(Instant.parse("2026-09-22T00:00:00Z"));
        OrchestratorProperties props = new OrchestratorProperties(
                5, Duration.ofMinutes(10), Duration.ofMinutes(30), Duration.ofMinutes(5));
        service = new EnvironmentService(backend, clock, props);
    }

    @Test
    void keylessRetryWithSameRequestIdMergesIntoSingleEnvAndSingleQuota() {
        // CI 用法：不带 idempotencyKey，只带 X-Request-Id；第一次开通失败
        backend.failNext("provision");
        OrchestratorException e = assertThrows(OrchestratorException.class,
                () -> service.create(TENANT, null, "ci-req-1"));
        assertEquals(FailureReason.BACKEND_FAILURE, e.reason());
        assertEquals(1, service.quotaUsed(TENANT), "首次失败登记的环境占一份配额");

        // 用同一个请求标识重试：必须复用同一个环境，不得新建、不得再占配额
        OperationResult retried = service.create(TENANT, null, "ci-req-1");
        assertEquals(EnvState.STOPPED, retried.env().state());
        assertEquals(1, service.list(TENANT).size(), "同一请求标识的重试不得拆成两个环境");
        assertEquals(1, service.quotaUsed(TENANT), "重试不得多占一份配额");
        assertEquals(2, backend.provisionCalls(), "失败一次 + 重试一次，总共只开通同一环境");
        assertTrue(backend.exists(retried.env().envId()));

        // 第三次同标识提交：直接命中冻结结论，不再触碰后端
        OperationResult replayed = service.create(TENANT, null, "ci-req-1");
        assertEquals(retried.env().envId(), replayed.env().envId());
        assertEquals(2, backend.provisionCalls(), "已成功标识的重放不得再调后端");
        assertEquals(1, service.quotaUsed(TENANT));
        log.info("keyless retry merged: env={} provisionCalls={} quota={}",
                retried.env().envId(), backend.provisionCalls(), service.quotaUsed(TENANT));
    }

    @Test
    void keylessCreatesWithoutAnyIdentityStayIndependentEvenAfterFailure() {
        // 两次都不带任何标识：即使第一次失败，第二次也是彼此独立的新环境
        backend.failNext("provision");
        assertThrows(OrchestratorException.class, () -> service.create(TENANT, null, null));
        OperationResult second = service.create(TENANT, null, null);

        assertEquals(2, service.list(TENANT).size(), "不带标识的两次创建各自独立");
        assertEquals(2, service.quotaUsed(TENANT), "各自占一份配额");
        assertNotEquals(service.list(TENANT).get(0).envId(), service.list(TENANT).get(1).envId());

        // 失败的那个直接清理，另一个独立环境不受影响
        EnvRecord stuck = service.list(TENANT).stream()
                .filter(r -> r.state() == EnvState.CREATING).findFirst().orElseThrow();
        service.destroy(TENANT, stuck.envId());
        assertEquals(1, service.quotaUsed(TENANT));
        assertTrue(backend.exists(second.env().envId()), "独立环境互不影响");
        log.info("keyless independent after failure: alive={} quota={}",
                second.env().envId(), service.quotaUsed(TENANT));
    }

    @Test
    void destroyWhileProvisionInFlightLeavesNoRealInstance() throws Exception {
        // 开通阻塞在中途时发起销毁：销毁必须等在途开通结束后把实例清干净
        backend.entered = new CountDownLatch(1);
        backend.release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<OperationResult> creating = pool.submit(() -> service.create(TENANT, null, "req-inflight"));
            assertTrue(backend.entered.await(5, TimeUnit.SECONDS), "前置：开通已在途");
            EnvRecord rec = service.list(TENANT).get(0);
            assertEquals(EnvState.CREATING, rec.state());

            Future<OperationResult> destroying = pool.submit(() -> service.destroy(TENANT, rec.envId(), "req-destroy-early"));
            Thread.sleep(200);
            assertFalse(destroying.isDone(), "开通在途时销毁必须等待开通落定，不得抢先清空");

            backend.release.countDown();
            OperationResult destroyed = destroying.get(5, TimeUnit.SECONDS);
            creating.get(5, TimeUnit.SECONDS);

            assertEquals(EnvState.DESTROYED, destroyed.env().state());
            assertEquals(EnvState.DESTROYED, service.get(TENANT, rec.envId()).state(),
                    "控制面必须显示已销毁");
            assertFalse(backend.exists(rec.envId()),
                    "控制面已销毁就不允许后端残留真实实例");
            assertEquals(0, service.quotaUsed(TENANT), "配额必须归还");
            log.info("destroy during provisioning converged: env={} state={} backendExists={}",
                    rec.envId(), service.get(TENANT, rec.envId()).state(), backend.exists(rec.envId()));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void receiptConclusionStableAcrossFailureAndRetry() {
        backend.failNext("provision");
        assertThrows(OrchestratorException.class, () -> service.create(TENANT, null, "req-stable"));

        RequestReceipt failed = service.getReceipt(TENANT, "req-stable");
        assertEquals(ReceiptStatus.FAILED, failed.status());
        assertTrue(failed.retryable(), "开通失败必须可重试");
        assertEquals(FailureReason.BACKEND_FAILURE, failed.failureReason());
        // 重试前反复回查结论稳定
        assertEquals(failed, service.getReceipt(TENANT, "req-stable"));

        OperationResult retried = service.create(TENANT, null, "req-stable");
        RequestReceipt settled = service.getReceipt(TENANT, "req-stable");
        assertEquals(ReceiptStatus.SUCCEEDED, settled.status(), "重试成功后回执收敛为成功");
        assertEquals(EnvState.STOPPED, settled.snapshotState());
        assertEquals(retried.env().envId(), settled.envId());

        // 后续其它操作（不同请求标识）不得改写该回执的冻结结论
        service.start(TENANT, retried.env().envId(), "req-other-op");
        RequestReceipt after = service.getReceipt(TENANT, "req-stable");
        assertEquals(settled, after, "历史回执必须冻结，不被后续操作改写");
        assertEquals(EnvState.STOPPED, after.snapshotState());
        log.info("receipt stable: failed={} -> settled={} (frozen across later ops)",
                failed.status(), after.status());
    }

    @Test
    void stuckCreatingEnvIsReapedAndQuotaReleased() {
        // 开通失败后调用方既不重试也不清理：回收器按 provisioningTtl 兜底清理
        backend.failNext("provision");
        assertThrows(OrchestratorException.class, () -> service.create(TENANT, null, "req-stuck"));
        EnvRecord stuck = service.list(TENANT).get(0);
        assertEquals(EnvState.CREATING, stuck.state());
        assertEquals(1, service.quotaUsed(TENANT));

        clock.advance(Duration.ofMinutes(4));
        assertEquals(0, service.reapIdle(), "未超 provisioningTtl 不得回收");

        clock.advance(Duration.ofMinutes(2));
        assertEquals(1, service.reapIdle(), "滞留超时必须被回收");
        assertEquals(EnvState.DESTROYED, service.get(TENANT, stuck.envId()).state());
        assertFalse(backend.exists(stuck.envId()), "不得残留真实实例");
        assertEquals(0, service.quotaUsed(TENANT), "卡住的环境不得永远占着配额");

        // 创建回执不被回收改写：仍是当初的可重试失败结论
        RequestReceipt receipt = service.getReceipt(TENANT, "req-stuck");
        assertEquals(ReceiptStatus.FAILED, receipt.status());
        assertTrue(receipt.retryable());
        log.info("stuck CREATING reaped: env={} quota={} receipt={}",
                stuck.envId(), service.quotaUsed(TENANT), receipt.status());
    }
}
