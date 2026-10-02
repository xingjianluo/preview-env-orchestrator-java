package com.github.xingjianluo.previewenvorchestratorjava.tests.orchestrator;

import com.github.xingjianluo.previewenvorchestratorjava.backend.InProcessRuntimeBackend;
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
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生命周期阶段判定：操作必须在环境当前阶段“说得通”，否则明确拒绝且原因可区分。
 */
class PhaseGateTest {

    private static final Logger log = LoggerFactory.getLogger(PhaseGateTest.class);
    private static final String TENANT = "tenant-a";

    /** 可阻塞在 provision 中途的后端，用于确定性复现“开通未完成”窗口。 */
    static class BlockingBackend extends InProcessRuntimeBackend {
        volatile CountDownLatch entered;
        volatile CountDownLatch release;

        /** 阻塞覆盖整个开通窗口：release 之前不会执行真实开通，记录稳定停在 CREATING。 */
        @Override
        public String provision(String envId, String tenantId) {
            CountDownLatch in = entered;
            CountDownLatch go = release;
            if (in != null) {
                in.countDown();
                try {
                    go.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return super.provision(envId, tenantId);
        }
    }

    private BlockingBackend backend;
    private EnvironmentService service;

    @BeforeEach
    void setUp() {
        backend = new BlockingBackend();
        OrchestratorProperties props = new OrchestratorProperties(
                5, Duration.ofMinutes(10), Duration.ofMinutes(30));
        service = new EnvironmentService(backend, new MutableClock(Instant.parse("2026-09-22T00:00:00Z")), props);
    }

    private EnvRecord creatingEnv(String key, String requestId, Future<?> future) throws Exception {
        // 等待开通线程进入 provision，此时控制面记录为 CREATING、后端尚无实例
        assertTrue(backend.entered.await(5, TimeUnit.SECONDS));
        EnvRecord rec = service.list(TENANT).get(0);
        assertEquals(EnvState.CREATING, rec.state(), "前置：环境仍处于开通阶段");
        assertFalse(backend.exists(rec.envId()), "前置：开通未完成，后端不应有实例");
        return rec;
    }

    @Test
    void startAndStopAreRejectedWhileProvisioningWithDistinguishableReason() throws Exception {
        // 先准备好同步点，再提交后台开通任务，避免任务在 latch 赋值前跑完
        backend.entered = new CountDownLatch(1);
        backend.release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<OperationResult> creating;
        try {
            creating = pool.submit(() -> service.create(TENANT, "phase-1", "req-create"));
            EnvRecord rec = creatingEnv("phase-1", "req-create", creating);

            // 开通未完成就启动：明确拒绝，不能被当成一次成功启动
            OrchestratorException startRej = assertThrows(OrchestratorException.class,
                    () -> service.start(TENANT, rec.envId(), "req-start-early"));
            assertEquals(FailureReason.ENV_STILL_PROVISIONING, startRej.reason(),
                    "必须与普通非法迁移 ILLEGAL_STATE_TRANSITION 可区分");
            assertTrue(startRej.getMessage().contains("start"), "拒绝信息需能区分是 start 被拒");

            // 开通未完成就停止：同样明确拒绝
            OrchestratorException stopRej = assertThrows(OrchestratorException.class,
                    () -> service.stop(TENANT, rec.envId(), "req-stop-early"));
            assertEquals(FailureReason.ENV_STILL_PROVISIONING, stopRej.reason());
            assertTrue(stopRej.getMessage().contains("stop"), "拒绝信息需能区分是 stop 被拒");

            // 关键回归：拒绝后记录仍是 CREATING，不得出现“列表显示已停/运行中、后端却无实例”
            assertEquals(EnvState.CREATING, service.get(TENANT, rec.envId()).state());
            assertFalse(backend.exists(rec.envId()));

            // 回执：失败但可安全重试
            RequestReceipt startReceipt = service.getReceipt(TENANT, "req-start-early");
            assertEquals(ReceiptStatus.FAILED, startReceipt.status());
            assertTrue(startReceipt.retryable());
            assertEquals(FailureReason.ENV_STILL_PROVISIONING, startReceipt.failureReason());

            // 开通继续并完成，随后的启动才真正作用于已存在的后端实例
            backend.release.countDown();
            OperationResult created = creating.get(5, TimeUnit.SECONDS);
            assertEquals(EnvState.STOPPED, created.env().state());

            OperationResult running = service.start(TENANT, rec.envId(), "req-start-ok");
            assertEquals(EnvState.RUNNING, running.env().state());
            assertTrue(backend.exists(rec.envId()), "开通完成后启动必须有真实后端实例对应");
            assertTrue(running.receipt().stateChanged());
            log.info("phase gating: early start/stop rejected; post-provision start converged RUNNING");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void provisionFailureThenRetryConvergesToConsistentState() {
        backend.failNext("provision");
        assertThrows(OrchestratorException.class, () -> service.create(TENANT, "phase-2", "req-c2"));
        EnvRecord stuck = service.list(TENANT).get(0);
        assertEquals(EnvState.CREATING, stuck.state());
        assertFalse(backend.exists(stuck.envId()));
        assertEquals(1, service.quotaUsed(TENANT));

        // 继续重试开通
        EnvRecord ready = service.create(TENANT, "phase-2", "req-c2").env();
        assertEquals(EnvState.STOPPED, ready.state());
        assertTrue(backend.exists(ready.envId()));
        assertEquals(1, service.quotaUsed(TENANT), "重试成功仍只占一份配额");
    }

    @Test
    void provisionFailureThenDestroyConvergesWithoutResidual() {
        backend.failNext("provision");
        assertThrows(OrchestratorException.class, () -> service.create(TENANT, "phase-3", "req-c3"));
        EnvRecord stuck = service.list(TENANT).get(0);
        assertEquals(EnvState.CREATING, stuck.state());

        // 不开了，直接清理：控制面与后端必须收敛到自洽终态
        OperationResult destroyed = service.destroy(TENANT, stuck.envId(), "req-cleanup");
        assertEquals(EnvState.DESTROYED, destroyed.env().state());
        assertFalse(backend.exists(stuck.envId()), "不得残留真实实例");
        assertEquals(0, service.quotaUsed(TENANT), "配额必须归还");
        assertEquals(EnvState.DESTROYED, service.get(TENANT, stuck.envId()).state(),
                "控制面状态与真实载体一致：都不存在/已销毁");

        // 清理回执成功且冻结
        RequestReceipt receipt = service.getReceipt(TENANT, "req-cleanup");
        assertEquals(ReceiptStatus.SUCCEEDED, receipt.status());
        assertEquals(EnvState.DESTROYED, receipt.snapshotState());
        assertTrue(receipt.stateChanged());
    }

    @Test
    void destroyWhileProvisionInFlightConvergesWithoutResidualInstance() throws Exception {
        // 开通卡在半路时发起销毁：销毁会等在途开通落定后再清理，
        // 最终控制面显示 DESTROYED 时后端绝不允许还留着真实实例
        backend.entered = new CountDownLatch(1);
        backend.release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<OperationResult> creating = pool.submit(() -> service.create(TENANT, "phase-5", "req-c5"));
            EnvRecord rec = creatingEnv("phase-5", "req-c5", creating);

            Future<OperationResult> destroying = pool.submit(
                    () -> service.destroy(TENANT, rec.envId(), "req-d5"));
            Thread.sleep(100); // 给销毁线程时间进入等待在途开通的临界区
            assertFalse(destroying.isDone(), "开通未落定前销毁不得抢跑完成（否则可能留下残留实例）");

            backend.release.countDown(); // 开通继续并完成
            OperationResult destroyed = destroying.get(5, TimeUnit.SECONDS);
            creating.get(5, TimeUnit.SECONDS);

            assertEquals(EnvState.DESTROYED, destroyed.env().state());
            assertEquals(EnvState.DESTROYED, service.get(TENANT, rec.envId()).state(),
                    "控制面终态必须是 DESTROYED");
            assertFalse(backend.exists(rec.envId()),
                    "控制面显示已销毁，后端就不得残留真实实例");
            assertEquals(0, service.quotaUsed(TENANT), "配额必须归还，与实际环境数量对齐");
            log.info("destroy during provisioning converged: env={} state={} backendExists={}",
                    rec.envId(), destroyed.env().state(), backend.exists(rec.envId()));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void ordinaryIllegalTransitionsRemainIllegalStateReason() {        EnvRecord env = service.create(TENANT, "phase-4", null).env();
        // STOPPED -> STOPPED：阶段说得通但目标重复，仍是普通非法迁移
        OrchestratorException e = assertThrows(OrchestratorException.class,
                () -> service.stop(TENANT, env.envId(), "req-dup-stop"));
        assertEquals(FailureReason.ILLEGAL_STATE_TRANSITION, e.reason());
        RequestReceipt r = service.getReceipt(TENANT, "req-dup-stop");
        assertEquals(ReceiptStatus.FAILED, r.status());
        assertFalse(r.retryable(), "重复停止再发结果不变，不应引导盲目重试");
    }
}
