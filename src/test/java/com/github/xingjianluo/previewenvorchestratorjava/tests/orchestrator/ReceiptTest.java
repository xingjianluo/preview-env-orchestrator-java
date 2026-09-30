package com.github.xingjianluo.previewenvorchestratorjava.tests.orchestrator;

import com.github.xingjianluo.previewenvorchestratorjava.backend.InProcessRuntimeBackend;
import com.github.xingjianluo.previewenvorchestratorjava.config.OrchestratorProperties;
import com.github.xingjianluo.previewenvorchestratorjava.domain.EnvState;
import com.github.xingjianluo.previewenvorchestratorjava.domain.FailureReason;
import com.github.xingjianluo.previewenvorchestratorjava.domain.OperationKind;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 请求回执：成功/处理中/失败（可安全重试）三态、结论稳定、后续操作不得改写历史结论。
 */
class ReceiptTest {

    private static final Logger log = LoggerFactory.getLogger(ReceiptTest.class);
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
    void receiptIsStableAcrossRepeatedPollsAndLaterOperations() {
        OperationResult created = service.create(TENANT, "rcpt-1", "req-create");
        String envId = created.env().envId();

        // 反复回查同一请求标识：结论稳定
        RequestReceipt a = service.getReceipt(TENANT, "req-create");
        RequestReceipt b = service.getReceipt(TENANT, "req-create");
        assertEquals(ReceiptStatus.SUCCEEDED, a.status());
        assertEquals(OperationKind.CREATE, a.kind());
        assertEquals(EnvState.STOPPED, a.snapshotState());
        assertTrue(a.stateChanged());
        assertEquals(a, b, "同一请求标识反复查询必须返回稳定结论");

        // 之后发生的别的操作（启动/停止）不得改写创建回执
        service.start(TENANT, envId, "req-start");
        service.stop(TENANT, envId, "req-stop");
        RequestReceipt after = service.getReceipt(TENANT, "req-create");
        assertEquals(ReceiptStatus.SUCCEEDED, after.status());
        assertEquals(EnvState.STOPPED, after.snapshotState(),
                "创建回执快照必须冻结在 STOPPED，不被后续 RUNNING/STOPPED 覆盖");
        assertTrue(after.stateChanged());
        assertNotNull(after.finishedAt());

        // 各操作各自有独立回执
        assertEquals(EnvState.RUNNING, service.getReceipt(TENANT, "req-start").snapshotState());
        assertEquals(EnvState.STOPPED, service.getReceipt(TENANT, "req-stop").snapshotState());
    }

    @Test
    void replayingSucceededRequestReturnsFrozenConclusionWithoutNewBackendCalls() {
        OperationResult created = service.create(TENANT, "rcpt-2", "req-dup");
        int provisionAfterCreate = backend.provisionCalls();

        // 用同一请求标识再次提交：幂等命中，结论冻结（保留首次成功结论），后端不被再次调用
        OperationResult replay = service.create(TENANT, "rcpt-2", "req-dup");
        assertEquals(created.env().envId(), replay.env().envId());
        assertEquals(ReceiptStatus.SUCCEEDED, replay.receipt().status());
        assertTrue(replay.receipt().stateChanged(), "冻结的是首次结论：首次创建确实改变了状态");
        assertEquals(provisionAfterCreate, backend.provisionCalls(), "重放不得再次调用后端开通");

        // start/stop 同样：重复提交同一 X-Request-Id 直接返回冻结结论，后端动作不重放
        OperationResult start1 = service.start(TENANT, created.env().envId(), "req-s");
        assertTrue(start1.receipt().stateChanged());
        OperationResult start2 = service.start(TENANT, created.env().envId(), "req-s");
        assertEquals(ReceiptStatus.SUCCEEDED, start2.receipt().status());
        assertEquals(EnvState.RUNNING, start2.receipt().snapshotState(), "冻结在首次成功结论");
        assertTrue(start2.receipt().stateChanged(), "冻结首次结论：首次启动确实改变了状态");
        log.info("frozen replay verified: provisionCalls={}", backend.provisionCalls());
    }

    @Test
    void retryableFailedStartConvergesAndReceiptIsStableAfterwards() {
        // 开通未完成阶段 start 失败：回执 FAILED + retryable
        OperationResult created = service.create(TENANT, "rcpt-3", "req-c3");
        // 人为停留在 CREATING 不易构造（开通是同步成功的），这里用后端失败注入覆盖失败-重试链路
        backend.failNext("start");
        OrchestratorException e = assertThrows(OrchestratorException.class,
                () -> service.start(TENANT, created.env().envId(), "req-start-flaky"));
        assertEquals(FailureReason.BACKEND_FAILURE, e.reason());

        RequestReceipt failed = service.getReceipt(TENANT, "req-start-flaky");
        assertEquals(ReceiptStatus.FAILED, failed.status());
        assertTrue(failed.retryable(), "后端失败可安全重试");
        assertEquals(EnvState.STOPPED, service.get(TENANT, created.env().envId()).state(),
                "启动失败不得留下幻影 RUNNING");

        // 用同一请求标识重试 -> 成功，回执从 FAILED 收敛为 SUCCEEDED，之后稳定
        OperationResult ok = service.start(TENANT, created.env().envId(), "req-start-flaky");
        assertEquals(EnvState.RUNNING, ok.env().state());
        RequestReceipt settled = service.getReceipt(TENANT, "req-start-flaky");
        assertEquals(ReceiptStatus.SUCCEEDED, settled.status());
        assertEquals(EnvState.RUNNING, settled.snapshotState());
        assertTrue(settled.stateChanged());

        // 后续停止不得再改写这个启动回执
        service.stop(TENANT, created.env().envId(), "req-stop-later");
        assertEquals(EnvState.RUNNING,
                service.getReceipt(TENANT, "req-start-flaky").snapshotState());
    }

    @Test
    void unknownAndKindConflictingRequestIdsAreDistinguishable() {
        OrchestratorException missing = assertThrows(OrchestratorException.class,
                () -> service.getReceipt(TENANT, "never-seen"));
        assertEquals(FailureReason.RECEIPT_NOT_FOUND, missing.reason());

        OperationResult created = service.create(TENANT, "rcpt-4", "shared-id");
        // 同一请求标识被另一类操作复用：明确拒绝，不能把 stop 结论写进 create 回执
        OrchestratorException conflict = assertThrows(OrchestratorException.class,
                () -> service.stop(TENANT, created.env().envId(), "shared-id"));
        assertEquals(FailureReason.REQUEST_KIND_CONFLICT, conflict.reason());
        RequestReceipt untouched = service.getReceipt(TENANT, "shared-id");
        assertEquals(OperationKind.CREATE, untouched.kind());
        assertEquals(ReceiptStatus.SUCCEEDED, untouched.status(), "原回执不被污染");
    }

    @Test
    void noRequestIdStillWorksAndReturnsServerGeneratedIdForPolling() {
        // 老的不带标识调用方式照旧可用：服务端生成 requestId 随结果返回
        OperationResult created = service.create(TENANT, null, null);
        String generated = created.receipt().requestId();
        assertNotNull(generated);
        assertEquals(ReceiptStatus.SUCCEEDED, service.getReceipt(TENANT, generated).status());

        // 先启动再停止，stop 同样支持无标识老用法并返回可回查的服务端标识
        service.start(TENANT, created.env().envId(), null);
        OperationResult stopped = service.stop(TENANT, created.env().envId(), null);
        assertNotNull(stopped.receipt().requestId());
        assertEquals(ReceiptStatus.SUCCEEDED,
                service.getReceipt(TENANT, stopped.receipt().requestId()).status());
    }
}
