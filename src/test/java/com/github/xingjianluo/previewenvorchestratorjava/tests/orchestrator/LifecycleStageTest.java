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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生命周期操作按“当前阶段是否说得通”判定：
 * - 开通未完成（CREATING）收到停止/启动必须明确拒绝，原因与普通非法迁移可区分；
 * - 不得出现列表显示已停止/运行中、后端却无对应实例的幻影状态；
 * - 开通失败卡住的环境，重试开通或直接清理最终都收敛到自洽结果。
 */
class LifecycleStageTest {

    private static final Logger log = LoggerFactory.getLogger(LifecycleStageTest.class);
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
        log.info("assert failure reason={} message={}", e.reason(), e.getMessage());
        assertEquals(reason, e.reason());
        return e;
    }

    /** 制造一个开通失败、停留 CREATING 的环境（控制面有记录，后端无实例）。 */
    private String stuckCreatingEnv(String key) {
        backend.failNext("provision");
        expectFail(FailureReason.BACKEND_FAILURE, () -> service.create(TENANT, key));
        EnvRecord stuck = service.list(TENANT).stream()
                .filter(r -> r.idempotencyKey().equals(key)).findFirst().orElseThrow();
        assertEquals(EnvState.CREATING, stuck.state());
        assertFalse(backend.exists(stuck.envId()), "开通失败后端不应残留实例");
        return stuck.envId();
    }

    @Test
    void stopWhileCreatingIsRejectedWithDistinctReason() {
        String envId = stuckCreatingEnv("creating-stop");

        expectFail(FailureReason.ENV_NOT_PROVISIONED, () -> service.stop(TENANT, envId));
        // 关键回归：拒绝后环境仍停留 CREATING，绝不能被落成 STOPPED（否则后续会出现幻影运行实例）
        assertEquals(EnvState.CREATING, service.get(TENANT, envId).state());
        assertFalse(backend.exists(envId));
    }

    @Test
    void startWhileCreatingIsRejectedWithDistinctReason() {
        String envId = stuckCreatingEnv("creating-start");

        expectFail(FailureReason.ENV_NOT_PROVISIONED, () -> service.start(TENANT, envId));
        assertEquals(EnvState.CREATING, service.get(TENANT, envId).state());
        assertFalse(backend.exists(envId), "拒绝启动后后端仍不得有无中生有的实例");
    }

    @Test
    void startStopReasonsAreDistinguishableFromPlainIllegalTransition() {
        EnvRecord ready = service.create(TENANT, "ready");
        // 已停止再停止：普通非法迁移，而不是 ENV_NOT_PROVISIONED
        expectFail(FailureReason.ILLEGAL_STATE_TRANSITION, () -> service.stop(TENANT, ready.envId()));
        // 运行中再启动：同样是普通非法迁移
        service.start(TENANT, ready.envId());
        expectFail(FailureReason.ILLEGAL_STATE_TRANSITION, () -> service.start(TENANT, ready.envId()));
    }

    @Test
    void startStopOnDestroyingRejectedWithTearingDownReason() {
        EnvRecord env = service.create(TENANT, "tearing");
        backend.failNext("destroy");
        expectFail(FailureReason.BACKEND_FAILURE, () -> service.destroy(TENANT, env.envId()));
        assertEquals(EnvState.DESTROYING, service.get(TENANT, env.envId()).state());

        expectFail(FailureReason.ENV_TEARING_DOWN, () -> service.start(TENANT, env.envId()));
        expectFail(FailureReason.ENV_TEARING_DOWN, () -> service.stop(TENANT, env.envId()));
    }

    @Test
    void stuckCreatingEnvRetriesProvisionAndConverges() {
        String envId = stuckCreatingEnv("retry-converge");
        assertEquals(1, service.quotaUsed(TENANT));

        // 同键重试开通 -> 进入 STOPPED，后端实例真实存在，配额仍只占一份
        EnvRecord ready = service.create(TENANT, "retry-converge");
        assertEquals(envId, ready.envId(), "重试不得产生第二个环境");
        assertEquals(EnvState.STOPPED, ready.state());
        assertTrue(backend.exists(envId), "重试成功后后端必须有对应实例");
        assertEquals(1, service.quotaUsed(TENANT));

        EnvRecord running = service.start(TENANT, envId);
        assertEquals(EnvState.RUNNING, running.state());
        assertTrue(backend.exists(envId));
    }

    @Test
    void stuckCreatingEnvCanBeDestroyedAndFullyConverges() {
        String envId = stuckCreatingEnv("cleanup-converge");
        assertEquals(1, service.quotaUsed(TENANT));

        // 开通失败卡住的环境直接清理 -> 终态 DESTROYED，无残留，配额归还
        EnvRecord destroyed = service.destroy(TENANT, envId);
        assertEquals(EnvState.DESTROYED, destroyed.state());
        assertFalse(backend.exists(envId), "清理后后端不得有残留实例");
        assertEquals(0, service.quotaUsed(TENANT), "收敛后控制面配额与实际占用必须一致");

        // 终态后任何实例操作仍被拒绝
        expectFail(FailureReason.ILLEGAL_STATE_TRANSITION, () -> service.start(TENANT, envId));
    }
}
