package com.github.xingjianluo.previewenvorchestratorjava.tests.orchestrator;

import com.github.xingjianluo.previewenvorchestratorjava.backend.InProcessRuntimeBackend;
import com.github.xingjianluo.previewenvorchestratorjava.config.OrchestratorProperties;
import com.github.xingjianluo.previewenvorchestratorjava.domain.EnvRecord;
import com.github.xingjianluo.previewenvorchestratorjava.domain.EnvState;
import com.github.xingjianluo.previewenvorchestratorjava.domain.FailureReason;
import com.github.xingjianluo.previewenvorchestratorjava.domain.RequestReceipt;
import com.github.xingjianluo.previewenvorchestratorjava.domain.RequestStatus;
import com.github.xingjianluo.previewenvorchestratorjava.service.EnvironmentService.Outcome;
import com.github.xingjianluo.previewenvorchestratorjava.service.EnvironmentService;
import com.github.xingjianluo.previewenvorchestratorjava.service.OrchestratorException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 请求回执：创建/启动/停止/销毁可按请求标识回查成功/处理中/失败（可安全重试），
 * 能看到作用环境与是否真的改变状态；同一标识反复回查结论稳定，成功历史不被后续操作改写。
 */
class RequestReceiptTest {

    private static final Logger log = LoggerFactory.getLogger(RequestReceiptTest.class);
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

    private OrchestratorException expectFail(FailureReason reason, Runnable op) {
        OrchestratorException e = assertThrows(OrchestratorException.class, op::run);
        assertEquals(reason, e.reason());
        return e;
    }

    @Test
    void createReceiptSucceedsAndIsStableAcrossRepeatedQueries() {
        Outcome created = service.createWithRequestId(TENANT, null, "create-1");
        String envId = created.record().envId();

        RequestReceipt r1 = service.getReceipt(TENANT, "create-1");
        assertEquals(RequestStatus.SUCCEEDED, r1.status());
        assertEquals("CREATE", r1.operation().name());
        assertEquals(envId, r1.envId());
        assertTrue(r1.stateChanged(), "首次开通真实改变了状态");
        assertEquals(EnvState.CREATING, r1.fromState());
        assertEquals(EnvState.STOPPED, r1.toState());

        // 环境后续又被启动/停止，历史回执结论不得被改写
        service.startWithRequestId(TENANT, envId, "start-1");
        service.stopWithRequestId(TENANT, envId, "stop-1");
        RequestReceipt r2 = service.getReceipt(TENANT, "create-1");
        assertEquals(RequestStatus.SUCCEEDED, r2.status());
        assertEquals(EnvState.CREATING, r2.fromState(), "历史 from/to 必须稳定");
        assertEquals(EnvState.STOPPED, r2.toState());
        assertTrue(r2.stateChanged());
        log.info("create receipt stable: {} {}->{} changed={}", r2.requestId(),
                r2.fromState(), r2.toState(), r2.stateChanged());
    }

    @Test
    void repeatedCreateWithSameRequestIdDoesNotReportStateChangeTwice() {
        service.createWithRequestId(TENANT, null, "create-dup");
        service.createWithRequestId(TENANT, null, "create-dup");
        RequestReceipt r = service.getReceipt(TENANT, "create-dup");
        assertEquals(RequestStatus.SUCCEEDED, r.status());
        // 第一次开通 changed=true，结论被冻结；重复提交不重复开通、不重复记变化
        assertTrue(r.stateChanged());
        assertEquals(1, backend.provisionCalls());
    }

    @Test
    void startStopReceiptsReportAffectedEnvAndChangeFlag() {
        Outcome created = service.createWithRequestId(TENANT, null, "c");
        String envId = created.record().envId();

        Outcome started = service.startWithRequestId(TENANT, envId, "go");
        RequestReceipt start = service.getReceipt(TENANT, "go");
        assertEquals(RequestStatus.SUCCEEDED, start.status());
        assertEquals(envId, start.envId());
        assertEquals(EnvState.STOPPED, start.fromState());
        assertEquals(EnvState.RUNNING, start.toState());
        assertTrue(start.stateChanged());

        Outcome stopped = service.stopWithRequestId(TENANT, envId, "halt");
        RequestReceipt stop = service.getReceipt(TENANT, "halt");
        assertEquals(EnvState.RUNNING, stop.fromState());
        assertEquals(EnvState.STOPPED, stop.toState());
        assertTrue(stop.stateChanged());
        assertEquals(com.github.xingjianluo.previewenvorchestratorjava.domain.StopCause.MANUAL,
                stopped.record().stopCause());
    }

    @Test
    void illegalStageRequestReceiptIsFailedAndSafelyRetryableThenStable() {
        // 制造一个 CREATING 卡住环境
        backend.failNext("provision");
        expectFail(FailureReason.BACKEND_FAILURE,
                () -> service.createWithRequestId(TENANT, null, "retryable-create"));
        RequestReceipt failed = service.getReceipt(TENANT, "retryable-create");
        assertEquals(RequestStatus.FAILED, failed.status());
        assertEquals(FailureReason.BACKEND_FAILURE, failed.failureReason(),
                "失败回执必须带可区分原因，调用方据此判断可安全重试");

        // 同一请求标识重试 -> 成功，结论推进为 SUCCEEDED，之后再查始终稳定
        Outcome retried = service.createWithRequestId(TENANT, null, "retryable-create");
        assertEquals(EnvState.STOPPED, retried.record().state());
        RequestReceipt ok = service.getReceipt(TENANT, "retryable-create");
        assertEquals(RequestStatus.SUCCEEDED, ok.status());
        // 失败那次与成功那次各调用一次后端开通，但只对应一个环境、一份配额
        assertEquals(1, service.list(TENANT).size());
        assertEquals(1, service.quotaUsed(TENANT));

        // 再次用同一标识提交：复用成功结论，不重复执行（后端开通调用数不再增加）
        int callsBefore = backend.provisionCalls();
        Outcome again = service.createWithRequestId(TENANT, null, "retryable-create");
        assertEquals(retried.record().envId(), again.record().envId());
        assertEquals(RequestStatus.SUCCEEDED, service.getReceipt(TENANT, "retryable-create").status());
        assertEquals(callsBefore, backend.provisionCalls(), "成功结论冻结后重复提交不得再次开通");
    }

    @Test
    void rejectedStopOnCreatingLeavesFailedReceiptAndEnvUntouched() {
        backend.failNext("provision");
        expectFail(FailureReason.BACKEND_FAILURE,
                () -> service.createWithRequestId(TENANT, null, "stuck-c"));
        String envId = service.list(TENANT).get(0).envId();

        expectFail(FailureReason.ENV_NOT_PROVISIONED,
                () -> service.stopWithRequestId(TENANT, envId, "bad-stop"));
        RequestReceipt receipt = service.getReceipt(TENANT, "bad-stop");
        assertEquals(RequestStatus.FAILED, receipt.status());
        assertEquals(FailureReason.ENV_NOT_PROVISIONED, receipt.failureReason());
        assertEquals(envId, receipt.envId());
        assertFalse(backend.exists(envId), "拒绝停止不得产生后端实例");
        assertEquals(EnvState.CREATING, service.get(TENANT, envId).state());
    }

    @Test
    void destroyReceiptReportsTerminalState() {
        Outcome created = service.createWithRequestId(TENANT, null, "cd");
        String envId = created.record().envId();
        Outcome destroyed = service.destroyWithRequestId(TENANT, envId, "kill");
        assertEquals(EnvState.DESTROYED, destroyed.record().state());

        RequestReceipt r = service.getReceipt(TENANT, "kill");
        assertEquals(RequestStatus.SUCCEEDED, r.status());
        assertEquals(EnvState.STOPPED, r.fromState());
        assertEquals(EnvState.DESTROYED, r.toState());
        assertTrue(r.stateChanged());
    }

    @Test
    void unknownAndCrossTenantReceiptQueriesAreRejected() {
        expectFail(FailureReason.REQUEST_NOT_FOUND, () -> service.getReceipt(TENANT, "nope"));

        service.createWithRequestId(TENANT, null, "own");
        expectFail(FailureReason.TENANT_FORBIDDEN, () -> service.getReceipt("tenant-b", "own"));
    }

    @Test
    void sameRequestIdAcrossDifferentOperationsIsConflict() {
        Outcome created = service.createWithRequestId(TENANT, null, "shared-id");
        expectFail(FailureReason.REQUEST_CONFLICT,
                () -> service.startWithRequestId(TENANT, created.record().envId(), "shared-id"));
    }
}
