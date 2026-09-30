package com.github.xingjianluo.previewenvorchestratorjava.tests.orchestrator;

import com.github.xingjianluo.previewenvorchestratorjava.backend.InProcessRuntimeBackend;
import com.github.xingjianluo.previewenvorchestratorjava.config.OrchestratorProperties;
import com.github.xingjianluo.previewenvorchestratorjava.domain.EnvRecord;
import com.github.xingjianluo.previewenvorchestratorjava.domain.EnvState;
import com.github.xingjianluo.previewenvorchestratorjava.service.EnvironmentService.Outcome;
import com.github.xingjianluo.previewenvorchestratorjava.service.EnvironmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 区分“调用方重试”与“两次独立创建”：
 * - 不带请求标识的两次创建必须是两个独立环境，互不影响；
 * - 带同一请求标识的重试合并到同一环境，只开通一次、只占一份配额。
 */
class RequestDedupTest {

    private static final Logger log = LoggerFactory.getLogger(RequestDedupTest.class);
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
    void twoCreatesWithoutRequestIdAreIndependentEnvs() {
        Outcome a = service.createWithRequestId(TENANT, null, null);
        Outcome b = service.createWithRequestId(TENANT, null, null);

        assertNotEquals(a.record().envId(), b.record().envId(),
                "两次不带标识的创建必须是两个独立环境");
        assertEquals(2, service.list(TENANT).size());
        assertEquals(2, service.quotaUsed(TENANT), "两个独立环境各占一份配额");
        assertEquals(2, backend.provisionCalls(), "必须各开通一次");
        assertNotEquals(a.requestId(), b.requestId(), "服务端当次生成的请求标识必须不同");
        log.info("keyless creates: envA={} envB={} quota={}",
                a.record().envId(), b.record().envId(), service.quotaUsed(TENANT));
    }

    @Test
    void destroyingOneKeylessEnvDoesNotDestroyTheOther() {
        Outcome a = service.createWithRequestId(TENANT, null, null);
        Outcome b = service.createWithRequestId(TENANT, null, null);
        service.destroy(TENANT, a.record().envId());

        assertEquals(EnvState.DESTROYED, service.get(TENANT, a.record().envId()).state());
        assertEquals(EnvState.STOPPED, service.get(TENANT, b.record().envId()).state(),
                "销毁其中一个不得顶掉另一个（旧实现无键撞键会连带消失）");
        assertEquals(1, service.quotaUsed(TENANT), "只有被销毁的那份归还配额，另一个仍占用");
    }

    @Test
    void sameRequestIdRetriesMergeIntoSingleEnvAndQuota() {
        Outcome first = service.createWithRequestId(TENANT, null, "req-xyz-1");
        Outcome second = service.createWithRequestId(TENANT, null, "req-xyz-1");
        Outcome third = service.createWithRequestId(TENANT, null, "req-xyz-1");

        assertEquals(first.record().envId(), second.record().envId());
        assertEquals(first.record().envId(), third.record().envId(),
                "带同一请求标识的重试必须合并到同一环境，不能拆成两个实例");
        assertEquals(1, backend.provisionCalls(), "后端只开通一次");
        assertEquals(1, service.quotaUsed(TENANT), "只占一份配额");
        assertEquals(1, service.list(TENANT).size());
        assertEquals("req-xyz-1", second.requestId());
    }

    @Test
    void sameIdempotencyKeyMergesEvenWithDifferentRequestIds() {
        // 旧风格 idempotencyKey 仍以环境去重为准，与回执请求标识解耦
        EnvRecord first = service.create(TENANT, "idem-key-9");
        Outcome retry = service.createWithRequestId(TENANT, "idem-key-9", "another-req-id");
        assertEquals(first.envId(), retry.record().envId(),
                "同 idempotencyKey 的重试即使请求标识不同也要合并到同一环境");
        assertEquals(1, backend.provisionCalls());
        assertEquals(1, service.quotaUsed(TENANT));
    }
}
