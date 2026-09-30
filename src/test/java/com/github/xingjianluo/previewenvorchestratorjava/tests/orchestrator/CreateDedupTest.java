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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 创建语义：区分“调用方自己重试”（同一请求标识）与“两次独立创建”（无标识）。
 */
class CreateDedupTest {

    private static final Logger log = LoggerFactory.getLogger(CreateDedupTest.class);
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
    void keylessCreatesAreIndependentEnvsEvenForSameTenant() {
        OperationResult r1 = service.create(TENANT, null, null);
        OperationResult r2 = service.create(TENANT, null, null);
        OperationResult r3 = service.create(TENANT, null, null);

        assertNotEquals(r1.env().envId(), r2.env().envId(), "无标识的两次创建必须是不同环境");
        assertNotEquals(r2.env().envId(), r3.env().envId());
        assertEquals(3, service.list(TENANT).size(), "同租户无标识创建不得互相顶替");
        assertEquals(3, backend.provisionCalls(), "每个环境各自开通一次");
        assertEquals(3, service.quotaUsed(TENANT), "每个环境各占一份配额");

        // 销毁其中一个，其余两个必须不受影响（回归“一个被销毁另一个也没了”）
        service.destroy(TENANT, r1.env().envId());
        List<EnvState> states = service.list(TENANT).stream().map(e -> e.state()).toList();
        long alive = states.stream().filter(s -> s != EnvState.DESTROYED).count();
        long dead = states.stream().filter(s -> s == EnvState.DESTROYED).count();
        assertEquals(2, alive, "只有被显式销毁的那一个离开存活集合，不得连带销毁其它独立环境");
        assertEquals(1, dead, "被销毁的环境恰有一个");
        assertTrue(states.contains(EnvState.DESTROYED));
        assertTrue(backend.exists(r2.env().envId()));
        assertTrue(backend.exists(r3.env().envId()));
        assertEquals(2, service.quotaUsed(TENANT), "仅销毁环境的配额归还，其余保留");
        log.info("keyless creates independent: {} / {} / {}",
                r1.env().envId(), r2.env().envId(), r3.env().envId());
    }

    @Test
    void sameKeyRetriesMergeIntoOneEnvAndOneQuota() {
        OperationResult r1 = service.create(TENANT, "ci-build-42", "req-42");
        OperationResult r2 = service.create(TENANT, "ci-build-42", "req-42"); // 调用方自己重试：同一请求标识
        OperationResult r3 = service.create(TENANT, "ci-build-42", "req-other"); // 不同请求标识，但同幂等键仍合并

        assertEquals(r1.env().envId(), r2.env().envId());
        assertEquals(r1.env().envId(), r3.env().envId(), "同一幂等键不得拆成两个实例");
        assertEquals(1, backend.provisionCalls(), "后端只能开通一次");
        assertEquals(1, service.quotaUsed(TENANT), "配额只能计一次");
        assertEquals(1, service.list(TENANT).size());
    }

    @Test
    void independentEnvsAndKeyedEnvCoexistWithoutColliding() {
        OperationResult keyed = service.create(TENANT, "pinned-key", null);
        OperationResult anon1 = service.create(TENANT, null, null);
        OperationResult anon2 = service.create(TENANT, null, null);
        OperationResult keyedRetry = service.create(TENANT, "pinned-key", null);

        assertEquals(keyed.env().envId(), keyedRetry.env().envId());
        assertNotEquals(keyed.env().envId(), anon1.env().envId());
        assertNotEquals(anon1.env().envId(), anon2.env().envId());
        assertEquals(3, service.list(TENANT).size());
        assertEquals(3, backend.provisionCalls());
        assertEquals(3, service.quotaUsed(TENANT));
    }

    @Test
    void failedKeyedCreateLeavesRetryableReceiptAndRetrySucceedsOnce() {
        backend.failNext("provision");
        OrchestratorException e = assertThrows(OrchestratorException.class,
                () -> service.create(TENANT, "retry-k", "req-retry"));
        assertEquals(FailureReason.BACKEND_FAILURE, e.reason());

        RequestReceipt failed = service.getReceipt(TENANT, "req-retry");
        assertEquals(ReceiptStatus.FAILED, failed.status());
        assertTrue(failed.retryable(), "开通失败必须可安全重试");
        assertEquals(1, service.quotaUsed(TENANT), "失败记录仍占一份配额");

        OperationResult retried = service.create(TENANT, "retry-k", "req-retry");
        assertEquals(EnvState.STOPPED, retried.env().state());
        assertEquals(1, service.list(TENANT).size());
        assertEquals(1, service.quotaUsed(TENANT), "重试不得重复计数配额");
        assertEquals(2, backend.provisionCalls(), "失败一次 + 成功一次；不多于两次");

        RequestReceipt settled = service.getReceipt(TENANT, "req-retry");
        assertEquals(ReceiptStatus.SUCCEEDED, settled.status(), "重试成功后回执收敛为成功");
        assertEquals(EnvState.STOPPED, settled.snapshotState());
    }
}
