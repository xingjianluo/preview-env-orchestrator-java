package com.github.xingjianluo.previewenvorchestratorjava.tests.orchestrator;

import com.github.xingjianluo.previewenvorchestratorjava.backend.InProcessRuntimeBackend;
import com.github.xingjianluo.previewenvorchestratorjava.config.OrchestratorProperties;
import com.github.xingjianluo.previewenvorchestratorjava.domain.EnvRecord;
import com.github.xingjianluo.previewenvorchestratorjava.domain.EnvState;
import com.github.xingjianluo.previewenvorchestratorjava.domain.StopCause;
import com.github.xingjianluo.previewenvorchestratorjava.lease.LeaseReaper;
import com.github.xingjianluo.previewenvorchestratorjava.service.EnvironmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 租约回收：时间由 MutableClock 确定性推进，不依赖真实等待。
 */
class LeaseReaperTest {

    private static final Logger log = LoggerFactory.getLogger(LeaseReaperTest.class);
    private static final String TENANT = "tenant-a";

    private InProcessRuntimeBackend backend;
    private MutableClock clock;
    private EnvironmentService service;
    private LeaseReaper reaper;

    @BeforeEach
    void setUp() {
        backend = new InProcessRuntimeBackend();
        clock = new MutableClock(Instant.parse("2026-09-22T00:00:00Z"));
        // RUNNING 空闲 10 分钟自动停止；STOPPED 空闲 30 分钟自动销毁
        OrchestratorProperties props = new OrchestratorProperties(
                5, Duration.ofMinutes(10), Duration.ofMinutes(30));
        service = new EnvironmentService(backend, clock, props);
        reaper = new LeaseReaper(service);
    }

    @Test
    void idleRunningEnvIsAutoStoppedWithLeaseCause() {
        EnvRecord env = service.create(TENANT, "lease-1");
        service.start(TENANT, env.envId());

        clock.advance(Duration.ofMinutes(9));
        assertEquals(0, reaper.sweepOnce(), "未到租约期限不得回收");
        assertEquals(EnvState.RUNNING, service.get(TENANT, env.envId()).state());

        clock.advance(Duration.ofMinutes(2)); // 累计空闲 11 分钟 > 10 分钟
        assertEquals(1, reaper.sweepOnce());
        EnvRecord stopped = service.get(TENANT, env.envId());
        assertEquals(EnvState.STOPPED, stopped.state());
        assertEquals(StopCause.LEASE_EXPIRED, stopped.stopCause(),
                "回收停止必须与手动停止可区分");
        log.info("lease auto-stop: env={} state={} cause={}", env.envId(), stopped.state(), stopped.stopCause());
    }

    @Test
    void idleStoppedEnvIsAutoDestroyedAndQuotaReclaimed() {
        EnvRecord env = service.create(TENANT, "lease-2");
        assertEquals(1, service.quotaUsed(TENANT));

        clock.advance(Duration.ofMinutes(31));
        assertEquals(1, reaper.sweepOnce());
        EnvRecord destroyed = service.get(TENANT, env.envId());
        assertEquals(EnvState.DESTROYED, destroyed.state());
        assertEquals(StopCause.LEASE_EXPIRED, destroyed.stopCause());
        assertEquals(0, service.quotaUsed(TENANT), "销毁后容量必须重新纳入配额");
        assertFalse(backend.exists(env.envId()));
        log.info("lease auto-destroy: env={} state={} cause={} quotaUsed={}",
                env.envId(), destroyed.state(), destroyed.stopCause(), service.quotaUsed(TENANT));
    }

    @Test
    void keepAliveRenewsLeaseAndPreventsReclaim() {
        EnvRecord env = service.create(TENANT, "lease-3");
        service.start(TENANT, env.envId());

        // 每 9 分钟续约一次，累计远超 10 分钟也不应被回收
        for (int i = 0; i < 5; i++) {
            clock.advance(Duration.ofMinutes(9));
            service.keepAlive(TENANT, env.envId());
            assertEquals(0, reaper.sweepOnce());
        }
        assertEquals(EnvState.RUNNING, service.get(TENANT, env.envId()).state());

        // 停止续约后到期即被回收
        clock.advance(Duration.ofMinutes(11));
        assertEquals(1, reaper.sweepOnce());
        assertEquals(EnvState.STOPPED, service.get(TENANT, env.envId()).state());
    }

    @Test
    void manualStopIsDistinguishableFromLeaseReclaim() {
        EnvRecord manual = service.create(TENANT, "m-1");
        service.start(TENANT, manual.envId());
        service.stop(TENANT, manual.envId());
        assertEquals(StopCause.MANUAL, service.get(TENANT, manual.envId()).stopCause());

        EnvRecord leased = service.create(TENANT, "l-1");
        service.start(TENANT, leased.envId());
        clock.advance(Duration.ofMinutes(11));
        reaper.sweepOnce();
        assertEquals(StopCause.LEASE_EXPIRED, service.get(TENANT, leased.envId()).stopCause());
        assertEquals(StopCause.MANUAL, service.get(TENANT, manual.envId()).stopCause(),
                "手动停止的标记不得被回收流程覆盖");
    }

    @Test
    void failedReclaimIsRetriedOnNextSweepWithoutLoss() {
        EnvRecord env = service.create(TENANT, "lease-4");
        clock.advance(Duration.ofMinutes(31));

        backend.failNext("destroy");
        assertEquals(0, reaper.sweepOnce(), "后端失败本轮回收失败但不得遗漏");
        assertEquals(EnvState.DESTROYING, service.get(TENANT, env.envId()).state(),
                "销毁中途失败应停留 DESTROYING 等待重试");
        assertEquals(1, service.quotaUsed(TENANT));

        assertEquals(1, reaper.sweepOnce(), "下一轮必须重试并完成回收");
        assertEquals(EnvState.DESTROYED, service.get(TENANT, env.envId()).state());
        assertEquals(0, service.quotaUsed(TENANT));
        assertFalse(backend.exists(env.envId()));
    }
}
