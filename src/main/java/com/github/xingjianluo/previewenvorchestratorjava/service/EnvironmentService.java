package com.github.xingjianluo.previewenvorchestratorjava.service;

import com.github.xingjianluo.previewenvorchestratorjava.backend.RuntimeBackend;
import com.github.xingjianluo.previewenvorchestratorjava.config.OrchestratorProperties;
import com.github.xingjianluo.previewenvorchestratorjava.domain.EnvRecord;
import com.github.xingjianluo.previewenvorchestratorjava.domain.EnvState;
import com.github.xingjianluo.previewenvorchestratorjava.domain.FailureReason;
import com.github.xingjianluo.previewenvorchestratorjava.domain.StopCause;
import com.github.xingjianluo.previewenvorchestratorjava.domain.Transitions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 编排控制面核心：生命周期状态机、幂等创建、租户隔离、配额与租约回收。
 *
 * 并发模型：
 * - 每条环境记录按 envId 分段加锁，迁移在锁内完成，杜绝并发覆盖；
 * - 创建按 (tenant, idempotencyKey) 在 ConcurrentHashMap.compute 内原子完成，
 *   同一 key 的重试/并发提交只会产生一条记录；
 * - 配额使用 per-tenant AtomicInteger CAS 预留，销毁时归还，不超卖。
 */
@Service
public class EnvironmentService {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentService.class);
    private static final int LOCK_STRIPES = 64;

    private final RuntimeBackend backend;
    private final Clock clock;
    private final OrchestratorProperties props;

    private final Map<String, EnvRecord> byId = new ConcurrentHashMap<>();
    private final Map<String, String> envIdByKey = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> quotaByTenant = new ConcurrentHashMap<>();
    private final ReentrantLock[] stripes = new ReentrantLock[LOCK_STRIPES];

    public EnvironmentService(RuntimeBackend backend, Clock clock, OrchestratorProperties props) {
        this.backend = backend;
        this.clock = clock;
        this.props = props;
        for (int i = 0; i < LOCK_STRIPES; i++) {
            stripes[i] = new ReentrantLock();
        }
    }

    private ReentrantLock lockFor(String key) {
        return stripes[Math.floorMod(key.hashCode(), LOCK_STRIPES)];
    }

    // ---------------------------------------------------------------- 创建

    /**
     * 幂等创建：同一 (tenant, idempotencyKey) 重复提交返回同一环境；
     * 若上次开通中途失败（停留 CREATING），本次调用即重试开通。
     */
    public EnvRecord create(String tenantId, String idempotencyKey) {
        String requestId = newRequestId();
        String key = tenantId + ":" + idempotencyKey;
        // compute 对同一 key 原子执行，并发重复提交只会有一条记录被创建
        String envId = envIdByKey.compute(key, (k, existingEnvId) -> existingEnvId == null
                ? reserveAndRegister(tenantId, idempotencyKey, requestId)
                : existingEnvId);
        return ensureProvisioned(byIdOrThrow(envId), requestId);
    }

    private String reserveAndRegister(String tenantId, String idempotencyKey, String requestId) {
        reserveQuota(tenantId, requestId);
        EnvRecord record = EnvRecord.newCreating(tenantId, idempotencyKey, Instant.now(clock));
        byId.put(record.envId(), record);
        log.info("[{}] create registered env={} tenant={} state={} (quota reserved)",
                requestId, record.envId(), tenantId, record.state());
        return record.envId();
    }

    /** 若记录仍处 CREATING，则（重）试开通；开通失败保持 CREATING 可再次重试。 */
    private EnvRecord ensureProvisioned(EnvRecord record, String requestId) {
        if (record.state() != EnvState.CREATING) {
            log.info("[{}] create idempotent-hit env={} tenant={} state={} (no new instance)",
                    requestId, record.envId(), record.tenantId(), record.state());
            return record;
        }
        ReentrantLock lock = lockFor(record.envId());
        lock.lock();
        try {
            EnvRecord current = byIdOrThrow(record.envId());
            if (current.state() != EnvState.CREATING) {
                return current; // 已被并发请求开通
            }
            try {
                backend.provision(current.envId(), current.tenantId());
            } catch (RuntimeException e) {
                log.warn("[{}] provision failed env={} state stays CREATING, retryable: {}",
                        requestId, current.envId(), e.getMessage());
                throw new OrchestratorException(FailureReason.BACKEND_FAILURE,
                        "provision failed for env " + current.envId() + ": " + e.getMessage());
            }
            return transitionLocked(current, EnvState.STOPPED, StopCause.NONE, requestId, "provision succeeded");
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------- 状态迁移

    public EnvRecord start(String tenantId, String envId) {
        String requestId = newRequestId();
        ReentrantLock lock = lockFor(envId);
        lock.lock();
        try {
            EnvRecord current = requireOwned(tenantId, envId, requestId);
            try {
                backend.start(envId);
            } catch (RuntimeException e) {
                throw backendFailure(requestId, envId, "start", e);
            }
            EnvRecord next = transitionLocked(current, EnvState.RUNNING, StopCause.NONE, requestId, "manual start");
            return next;
        } finally {
            lock.unlock();
        }
    }

    public EnvRecord stop(String tenantId, String envId) {
        return stopInternal(tenantId, envId, StopCause.MANUAL, newRequestId());
    }

    private EnvRecord stopInternal(String tenantId, String envId, StopCause cause, String requestId) {
        ReentrantLock lock = lockFor(envId);
        lock.lock();
        try {
            EnvRecord current = tenantId == null
                    ? byIdOrThrow(envId)
                    : requireOwned(tenantId, envId, requestId);
            try {
                backend.stop(envId);
            } catch (RuntimeException e) {
                throw backendFailure(requestId, envId, "stop", e);
            }
            return transitionLocked(current, EnvState.STOPPED, cause, requestId,
                    cause == StopCause.LEASE_EXPIRED ? "lease expired, auto stop" : "manual stop");
        } finally {
            lock.unlock();
        }
    }

    /**
     * 幂等销毁：非终态迁入 DESTROYING 并调用后端清理；后端失败保持 DESTROYING 可重试；
     * 已 DESTROYED 直接返回当前记录。配额在抵达 DESTROYED 时归还。
     */
    public EnvRecord destroy(String tenantId, String envId) {
        return destroyInternal(tenantId, envId, StopCause.MANUAL, newRequestId());
    }

    private EnvRecord destroyInternal(String tenantId, String envId, StopCause cause, String requestId) {
        ReentrantLock lock = lockFor(envId);
        lock.lock();
        try {
            EnvRecord current = tenantId == null
                    ? byIdOrThrow(envId)
                    : requireOwned(tenantId, envId, requestId);
            if (current.state() == EnvState.DESTROYED) {
                log.info("[{}] destroy idempotent-hit env={} already DESTROYED", requestId, envId);
                return current;
            }
            EnvState from = current.state();
            // DESTROYING 是重试入口：上次销毁中途失败，允许再次进入清理流程
            if (from != EnvState.DESTROYING && !Transitions.isAllowed(from, EnvState.DESTROYING)) {
                log.info("[{}] reject env={} tenant={} {}", requestId, envId, current.tenantId(),
                        Transitions.explain(from, EnvState.DESTROYING));
                throw new OrchestratorException(FailureReason.ILLEGAL_STATE_TRANSITION,
                        Transitions.explain(from, EnvState.DESTROYING));
            }
            try {
                backend.destroy(envId); // 后端幂等，同时清理开通失败可能留下的残留
            } catch (RuntimeException e) {
                EnvRecord destroying = put(current.withState(EnvState.DESTROYING, cause, now()));
                log.warn("[{}] destroy failed env={} {} -> DESTROYING kept, retryable: {}",
                        requestId, envId, from, e.getMessage());
                throw new OrchestratorException(FailureReason.BACKEND_FAILURE,
                        "destroy failed for env " + envId + ": " + e.getMessage());
            }
            EnvRecord destroying = from == EnvState.DESTROYING ? current
                    : put(current.withState(EnvState.DESTROYING, cause, now()));
            EnvRecord destroyed = transitionLocked(destroying, EnvState.DESTROYED, cause, requestId,
                    cause == StopCause.LEASE_EXPIRED ? "lease expired, auto destroy" : "manual destroy");
            releaseQuota(destroyed.tenantId(), requestId);
            return destroyed;
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------- 查询 / 续约

    public EnvRecord get(String tenantId, String envId) {
        return requireOwned(tenantId, envId, newRequestId());
    }

    public List<EnvRecord> list(String tenantId) {
        List<EnvRecord> result = new ArrayList<>();
        for (EnvRecord r : byId.values()) {
            if (r.tenantId().equals(tenantId)) {
                result.add(r);
            }
        }
        return result;
    }

    /** 租约续约：刷新活跃时间，仅 RUNNING/STOPPED 可续约。 */
    public EnvRecord keepAlive(String tenantId, String envId) {
        String requestId = newRequestId();
        ReentrantLock lock = lockFor(envId);
        lock.lock();
        try {
            EnvRecord current = requireOwned(tenantId, envId, requestId);
            if (current.state() != EnvState.RUNNING && current.state() != EnvState.STOPPED) {
                throw new OrchestratorException(FailureReason.ILLEGAL_STATE_TRANSITION,
                        "keepAlive requires RUNNING or STOPPED, actual " + current.state());
            }
            EnvRecord next = put(current.touch(now()));
            log.info("[{}] keepAlive env={} tenant={} state={} lastActiveAt={}",
                    requestId, envId, tenantId, next.state(), next.lastActiveAt());
            return next;
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------- 租约回收

    /**
     * 由 LeaseReaper 调用：回收空闲超时环境。
     * RUNNING 空闲超 idleStopTtl 自动停止；STOPPED 空闲超 idleDestroyTtl 自动销毁。
     * 返回本轮回收（状态发生变化）的环境数。
     */
    public int reapIdle() {
        Instant now = now();
        int reclaimed = 0;
        for (EnvRecord r : List.copyOf(byId.values())) {
            try {
                if (r.state() == EnvState.RUNNING
                        && r.lastActiveAt().plus(props.getIdleStopTtl()).isBefore(now)) {
                    stopInternal(null, r.envId(), StopCause.LEASE_EXPIRED, newRequestId());
                    reclaimed++;
                } else if (r.state() == EnvState.STOPPED
                        && r.lastActiveAt().plus(props.getIdleDestroyTtl()).isBefore(now)) {
                    destroyInternal(null, r.envId(), StopCause.LEASE_EXPIRED, newRequestId());
                    reclaimed++;
                } else if (r.state() == EnvState.DESTROYING) {
                    // 上次销毁中途失败的残留：每轮重试直至抵达终态，不遗漏
                    destroyInternal(null, r.envId(), r.stopCause(), newRequestId());
                    reclaimed++;
                }
            } catch (OrchestratorException e) {
                // 后端失败等：保持现状，下一轮重试，不遗漏
                log.warn("reap skipped env={} reason={} detail={}", r.envId(), e.reason(), e.getMessage());
            }
        }
        return reclaimed;
    }

    // ---------------------------------------------------------------- 内部工具

    private EnvRecord transitionLocked(EnvRecord current, EnvState to, StopCause cause,
                                       String requestId, String basis) {
        EnvState from = current.state();
        if (!Transitions.isAllowed(from, to)) {
            log.info("[{}] reject env={} tenant={} {} | basis={}", requestId, current.envId(),
                    current.tenantId(), Transitions.explain(from, to), basis);
            throw new OrchestratorException(FailureReason.ILLEGAL_STATE_TRANSITION,
                    Transitions.explain(from, to));
        }
        EnvRecord next = put(current.withState(to, cause, now()));
        log.info("[{}] transition env={} tenant={} {} -> {} cause={} | basis={}",
                requestId, current.envId(), current.tenantId(), from, to, cause, basis);
        return next;
    }

    private EnvRecord requireOwned(String tenantId, String envId, String requestId) {
        EnvRecord record = byId.get(envId);
        if (record == null) {
            log.info("[{}] reject env={} tenant={} reason=ENV_NOT_FOUND", requestId, envId, tenantId);
            throw new OrchestratorException(FailureReason.ENV_NOT_FOUND, "env not found: " + envId);
        }
        if (!record.tenantId().equals(tenantId)) {
            log.info("[{}] reject env={} tenant={} owner={} reason=TENANT_FORBIDDEN",
                    requestId, envId, tenantId, record.tenantId());
            throw new OrchestratorException(FailureReason.TENANT_FORBIDDEN,
                    "env " + envId + " belongs to another tenant");
        }
        return record;
    }

    private void reserveQuota(String tenantId, String requestId) {
        AtomicInteger used = quotaByTenant.computeIfAbsent(tenantId, t -> new AtomicInteger());
        while (true) {
            int cur = used.get();
            if (cur >= props.getMaxEnvsPerTenant()) {
                log.info("[{}] reject tenant={} reason=QUOTA_EXCEEDED used={} max={}",
                        requestId, tenantId, cur, props.getMaxEnvsPerTenant());
                throw new OrchestratorException(FailureReason.QUOTA_EXCEEDED,
                        "tenant " + tenantId + " quota exceeded: " + cur + "/"
                                + props.getMaxEnvsPerTenant());
            }
            if (used.compareAndSet(cur, cur + 1)) {
                return;
            }
        }
    }

    private void releaseQuota(String tenantId, String requestId) {
        AtomicInteger used = quotaByTenant.get(tenantId);
        if (used != null) {
            int after = used.decrementAndGet();
            log.info("[{}] quota released tenant={} used={}", requestId, tenantId, after);
        }
    }

    public int quotaUsed(String tenantId) {
        AtomicInteger used = quotaByTenant.get(tenantId);
        return used == null ? 0 : used.get();
    }

    private OrchestratorException backendFailure(String requestId, String envId, String op, RuntimeException e) {
        log.warn("[{}] backend {} failed env={}: {}", requestId, op, envId, e.getMessage());
        return new OrchestratorException(FailureReason.BACKEND_FAILURE,
                op + " failed for env " + envId + ": " + e.getMessage());
    }

    private EnvRecord byIdOrThrow(String envId) {
        EnvRecord r = byId.get(envId);
        if (r == null) {
            throw new OrchestratorException(FailureReason.ENV_NOT_FOUND, "env not found: " + envId);
        }
        return r;
    }

    private EnvRecord put(EnvRecord record) {
        byId.put(record.envId(), record);
        return record;
    }

    private Instant now() {
        return Instant.now(clock);
    }

    private static String newRequestId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
