package com.github.xingjianluo.previewenvorchestratorjava.tests.orchestrator;

import com.github.xingjianluo.previewenvorchestratorjava.backend.InProcessRuntimeBackend;
import com.github.xingjianluo.previewenvorchestratorjava.config.OrchestratorProperties;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 创建重试一致性（CI 用法回归）：不带 body 里的幂等键、只带 X-Request-Id 的创建，
 * 同一请求标识的重试必须合并到同一个环境——只开通一次、只占一份配额；
 * 两次都不带任何标识的创建仍然各自独立。
 */
class CreateRetryConsistencyTest {

    private static final Logger log = LoggerFactory.getLogger(CreateRetryConsistencyTest.class);
    private static final String TENANT = "tenant-a";

    private InProcessRuntimeBackend backend;
    private EnvironmentService service;

    @BeforeEach
    void setUp() {
        backend = new InProcessRuntimeBackend();
        OrchestratorProperties props = new OrchestratorProperties(
                5, Duration.ofMinutes(10), Duration.ofMinutes(30));
        service = new EnvironmentService(backend, new MutableClock(Instant.parse("2026-09-22T00:00:00Z")), props);
    }

    @Test
    void keylessRetryWithSameRequestIdMergesIntoOneEnvAndOneQuota() {
        // CI 用法：不带 idempotencyKey，只带自己生成的请求标识；失败后原样重试
        OperationResult first = service.create(TENANT, null, "ci-req-1");
        OperationResult retry = service.create(TENANT, null, "ci-req-1");

        assertEquals(first.env().envId(), retry.env().envId(),
                "同一请求标识的重试必须复用同一个环境，不得拆成两个");
        assertEquals(1, service.list(TENANT).size(), "列表里只能有一个环境");
        assertEquals(1, backend.provisionCalls(), "后端只能开通一次");
        assertEquals(1, service.quotaUsed(TENANT), "配额只能占一份");
        assertEquals(first.receipt(), retry.receipt(), "重试命中的是冻结的同一份成功回执");
        log.info("keyless same-request-id retry merged: env={} quotaUsed={}",
                first.env().envId(), service.quotaUsed(TENANT));
    }

    @Test
    void keylessRetryAfterProvisionFailureReusesStuckEnvInsteadOfCreatingSecond() {
        // 第一次开通中途失败：记录停留 CREATING、占一份配额
        backend.failNext("provision");
        OrchestratorException e = assertThrows(OrchestratorException.class,
                () -> service.create(TENANT, null, "ci-req-2"));
        assertEquals(FailureReason.BACKEND_FAILURE, e.reason());
        String stuckEnvId = service.list(TENANT).get(0).envId();
        assertEquals(EnvState.CREATING, service.get(TENANT, stuckEnvId).state());
        assertEquals(1, service.quotaUsed(TENANT));

        // 用同一个请求标识重试：必须找回卡住的那个环境继续开通，而不是再登记一个
        OperationResult retried = service.create(TENANT, null, "ci-req-2");
        assertEquals(stuckEnvId, retried.env().envId(),
                "失败后同标识重试必须复用首次登记的环境");
        assertEquals(EnvState.STOPPED, retried.env().state());
        assertEquals(1, service.list(TENANT).size(), "不得多出第二个环境");
        assertEquals(1, service.quotaUsed(TENANT), "配额不得重复计数");
        assertEquals(2, backend.provisionCalls(), "失败一次 + 重试成功一次，恰两次开通调用");
        assertTrue(backend.exists(stuckEnvId));
        log.info("keyless failed create retried with same request id: env={} state={} quotaUsed={}",
                stuckEnvId, retried.env().state(), service.quotaUsed(TENANT));
    }

    @Test
    void keylessCreatesWithDifferentRequestIdsRemainIndependent() {
        OperationResult r1 = service.create(TENANT, null, "ci-req-a");
        OperationResult r2 = service.create(TENANT, null, "ci-req-b");

        assertNotEquals(r1.env().envId(), r2.env().envId(),
                "不同请求标识（且都无幂等键）的创建必须各自独立");
        assertEquals(2, service.list(TENANT).size());
        assertEquals(2, backend.provisionCalls());
        assertEquals(2, service.quotaUsed(TENANT));
    }

    @Test
    void receiptConclusionIsStableBeforeAndAfterRetry() {
        backend.failNext("provision");
        assertThrows(OrchestratorException.class, () -> service.create(TENANT, null, "ci-req-3"));

        // 重试前：失败结论可回查、可安全重试，且已绑定首次登记的环境
        RequestReceipt failed = service.getReceipt(TENANT, "ci-req-3");
        assertEquals(ReceiptStatus.FAILED, failed.status());
        assertTrue(failed.retryable());
        String envId = service.list(TENANT).get(0).envId();
        assertEquals(envId, failed.envId(), "失败回执必须记得已登记的环境，供重试复用");

        // 重试成功：同一请求标识的回执收敛为成功，指向同一个环境
        OperationResult retried = service.create(TENANT, null, "ci-req-3");
        assertEquals(ReceiptStatus.SUCCEEDED, retried.receipt().status());
        assertEquals(envId, retried.receipt().envId());

        // 重试后反复回查：结论冻结稳定；再次提交同标识请求也只是重放，不再触碰后端
        RequestReceipt settled1 = service.getReceipt(TENANT, "ci-req-3");
        RequestReceipt settled2 = service.getReceipt(TENANT, "ci-req-3");
        assertEquals(settled1, settled2, "回执结论必须稳定");
        assertEquals(ReceiptStatus.SUCCEEDED, settled1.status());
        assertEquals(EnvState.STOPPED, settled1.snapshotState());

        OperationResult replayed = service.create(TENANT, null, "ci-req-3");
        assertEquals(envId, replayed.env().envId());
        assertEquals(2, backend.provisionCalls(), "成功结论冻结后重放不得再次开通");
        assertEquals(1, service.quotaUsed(TENANT));
        log.info("receipt stable across retry: request=ci-req-3 env={} status={}",
                envId, settled1.status());
    }

    @Test
    void failedKeylessCreateCleanedUpByDestroyReleasesQuotaAndResidual() {
        // 开通失败后调用方不再重试、直接清理：控制面与真实载体收敛一致
        backend.failNext("provision");
        assertThrows(OrchestratorException.class, () -> service.create(TENANT, null, "ci-req-4"));
        String envId = service.list(TENANT).get(0).envId();
        assertEquals(EnvState.CREATING, service.get(TENANT, envId).state());
        assertEquals(1, service.quotaUsed(TENANT));

        OperationResult destroyed = service.destroy(TENANT, envId, "ci-req-4-cleanup");
        assertEquals(EnvState.DESTROYED, destroyed.env().state());
        assertFalse(backend.exists(envId), "控制面显示已销毁就不得再有真实实例");
        assertEquals(0, service.quotaUsed(TENANT), "配额必须与实际环境数量对齐");

        // 清理后再用原请求标识重试创建：原环境已销毁，登记新环境是合法的新一次尝试
        OperationResult recreated = service.create(TENANT, null, "ci-req-4");
        assertEquals(EnvState.STOPPED, recreated.env().state());
        assertEquals(1, service.list(TENANT).stream().filter(r -> r.state() != EnvState.DESTROYED).count());
        assertEquals(1, service.quotaUsed(TENANT), "新旧环境交替后配额仍与实际存活数一致");
        log.info("failed create cleaned then recreated: old={} new={} quotaUsed={}",
                envId, recreated.env().envId(), service.quotaUsed(TENANT));
    }
}
