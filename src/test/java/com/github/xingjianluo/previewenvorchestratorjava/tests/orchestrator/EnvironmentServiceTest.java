package com.github.xingjianluo.previewenvorchestratorjava.tests.orchestrator;

import com.github.xingjianluo.previewenvorchestratorjava.backend.InProcessRuntimeBackend;
import com.github.xingjianluo.previewenvorchestratorjava.config.OrchestratorProperties;
import com.github.xingjianluo.previewenvorchestratorjava.domain.EnvRecord;
import com.github.xingjianluo.previewenvorchestratorjava.domain.EnvState;
import com.github.xingjianluo.previewenvorchestratorjava.domain.FailureReason;
import com.github.xingjianluo.previewenvorchestratorjava.domain.StopCause;
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

class EnvironmentServiceTest {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentServiceTest.class);
    private static final String TENANT = "tenant-a";

    private InProcessRuntimeBackend backend;
    private MutableClock clock;
    private EnvironmentService service;

    @BeforeEach
    void setUp() {
        backend = new InProcessRuntimeBackend();
        clock = new MutableClock(Instant.parse("2026-09-22T00:00:00Z"));
        OrchestratorProperties props = new OrchestratorProperties(
                2, Duration.ofMinutes(10), Duration.ofMinutes(30));
        service = new EnvironmentService(backend, clock, props);
    }

    private OrchestratorException expectFail(FailureReason reason, Runnable op) {
        OrchestratorException e = assertThrows(OrchestratorException.class, op::run);
        log.info("assert failure reason={} message={}", e.reason(), e.getMessage());
        assertEquals(reason, e.reason());
        return e;
    }

    @Test
    void fullLifecycleFollowsStateMachine() {
        EnvRecord created = service.create(TENANT, "key-1");
        assertEquals(EnvState.STOPPED, created.state());

        EnvRecord running = service.start(TENANT, created.envId());
        assertEquals(EnvState.RUNNING, running.state());

        EnvRecord stopped = service.stop(TENANT, created.envId());
        assertEquals(EnvState.STOPPED, stopped.state());
        assertEquals(StopCause.MANUAL, stopped.stopCause());

        EnvRecord destroyed = service.destroy(TENANT, created.envId());
        assertEquals(EnvState.DESTROYED, destroyed.state());
        assertFalse(backend.exists(created.envId()));
        assertEquals(0, service.quotaUsed(TENANT), "销毁后配额必须归还");
    }

    @Test
    void duplicateCreateWithSameKeyDoesNotCreateSecondInstance() {
        EnvRecord first = service.create(TENANT, "dup-key");
        EnvRecord second = service.create(TENANT, "dup-key");
        assertEquals(first.envId(), second.envId(), "相同幂等键不得产生第二个环境");
        assertEquals(1, backend.provisionCalls(), "后端开通只能发生一次");
        assertEquals(1, service.quotaUsed(TENANT), "配额不得重复计数");
        assertEquals(1, service.list(TENANT).size());
    }

    @Test
    void illegalAndDuplicateTransitionsAreRejected() {
        EnvRecord env = service.create(TENANT, "key-t");
        // STOPPED -> STOPPED 重复停止
        expectFail(FailureReason.ILLEGAL_STATE_TRANSITION,
                () -> service.stop(TENANT, env.envId()));
        service.start(TENANT, env.envId());
        // RUNNING -> RUNNING 重复启动
        expectFail(FailureReason.ILLEGAL_STATE_TRANSITION,
                () -> service.start(TENANT, env.envId()));
        service.destroy(TENANT, env.envId());
        // 终态后任何迁移都被拒绝
        expectFail(FailureReason.ILLEGAL_STATE_TRANSITION,
                () -> service.start(TENANT, env.envId()));
        // 重复销毁幂等返回，不报错
        assertEquals(EnvState.DESTROYED, service.destroy(TENANT, env.envId()).state());
    }

    @Test
    void crossTenantAccessIsForbidden() {
        EnvRecord env = service.create(TENANT, "key-x");
        expectFail(FailureReason.TENANT_FORBIDDEN, () -> service.get("tenant-b", env.envId()));
        expectFail(FailureReason.TENANT_FORBIDDEN, () -> service.start("tenant-b", env.envId()));
        expectFail(FailureReason.TENANT_FORBIDDEN, () -> service.destroy("tenant-b", env.envId()));
        expectFail(FailureReason.ENV_NOT_FOUND, () -> service.get(TENANT, "no-such-env"));
        assertEquals(0, service.list("tenant-b").size(), "跨租户列表必须隔离");
    }

    @Test
    void quotaExhaustionIsRejectedAndRecoveredAfterDestroy() {
        service.create(TENANT, "q1");
        service.create(TENANT, "q2");
        expectFail(FailureReason.QUOTA_EXCEEDED, () -> service.create(TENANT, "q3"));
        assertEquals(2, service.quotaUsed(TENANT));

        // 其他租户配额互不影响
        service.create("tenant-b", "q1");
        assertEquals(1, service.quotaUsed("tenant-b"));

        EnvRecord victim = service.list(TENANT).get(0);
        service.destroy(TENANT, victim.envId());
        assertEquals(1, service.quotaUsed(TENANT));
        service.create(TENANT, "q4"); // 释放后可再开通，不超卖
        assertEquals(2, service.quotaUsed(TENANT));
    }

    @Test
    void failedProvisionIsRetryableWithoutResidualOrDoubleCounting() {
        backend.failNext("provision");
        expectFail(FailureReason.BACKEND_FAILURE, () -> service.create(TENANT, "retry-key"));
        assertEquals(1, service.quotaUsed(TENANT), "失败记录仍占配额，重试不得重复计数");

        EnvRecord retried = service.create(TENANT, "retry-key");
        assertEquals(EnvState.STOPPED, retried.state());
        assertEquals(1, service.list(TENANT).size(), "重试不得产生第二个环境");
        assertEquals(1, service.quotaUsed(TENANT));
        assertTrue(backend.exists(retried.envId()));
    }

    @Test
    void failedDestroyIsRetryableAndCleansResidual() {
        EnvRecord env = service.create(TENANT, "d-key");
        backend.failNext("destroy");
        expectFail(FailureReason.BACKEND_FAILURE, () -> service.destroy(TENANT, env.envId()));
        assertEquals(EnvState.DESTROYING, service.get(TENANT, env.envId()).state(),
                "销毁失败应停留 DESTROYING 可重试");
        assertEquals(1, service.quotaUsed(TENANT), "未抵达终态不得释放配额");

        EnvRecord destroyed = service.destroy(TENANT, env.envId());
        assertEquals(EnvState.DESTROYED, destroyed.state());
        assertFalse(backend.exists(env.envId()), "残留实例必须被清理");
        assertEquals(0, service.quotaUsed(TENANT), "配额不得遗漏或重复计数");
    }
}
