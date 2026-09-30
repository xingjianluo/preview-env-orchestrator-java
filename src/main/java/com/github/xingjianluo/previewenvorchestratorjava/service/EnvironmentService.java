package com.github.xingjianluo.previewenvorchestratorjava.service;

import com.github.xingjianluo.previewenvorchestratorjava.backend.RuntimeBackend;
import com.github.xingjianluo.previewenvorchestratorjava.config.OrchestratorProperties;
import com.github.xingjianluo.previewenvorchestratorjava.domain.EnvRecord;
import com.github.xingjianluo.previewenvorchestratorjava.domain.EnvState;
import com.github.xingjianluo.previewenvorchestratorjava.domain.FailureReason;
import com.github.xingjianluo.previewenvorchestratorjava.domain.OperationType;
import com.github.xingjianluo.previewenvorchestratorjava.domain.RequestReceipt;
import com.github.xingjianluo.previewenvorchestratorjava.domain.StopCause;
import com.github.xingjianluo.previewenvorchestratorjava.domain.Transitions;
import com.github.xingjianluo.previewenvorchestratorjava.receipt.ReceiptClaim;
import com.github.xingjianluo.previewenvorchestratorjava.receipt.ReceiptStore;
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
 * 编排控制面核心：生命周期状态机、区分重试与独立创建、租户隔离、配额、租约回收与请求回执。
 *
 * 并发模型：
 * - 每条环境记录按 envId 分段加锁，迁移在锁内完成，杜绝并发覆盖；
 * - 创建按 (tenant, 去重键) 去重：调用方显式提供同一请求标识的重试合并到同一环境，
 *   未提供标识的两次创建每次生成唯一去重键，得到两个相互独立的环境；
 * - 配额使用 per-tenant AtomicInteger CAS 预留，销毁时归还，不超卖；
 * - 所有面向调用方的写操作以 requestId 在 {@link ReceiptStore} 留回执，结论稳定可回查。
 */
@Service
public class EnvironmentService {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentService.class);
    private static final int LOCK_STRIPES = 64;

    private final RuntimeBackend backend;
    private final Clock clock;
    private final OrchestratorProperties props;
    private final ReceiptStore receipts;

    private final Map<String, EnvRecord> byId = new ConcurrentHashMap<>();
    private final Map<String, String> envIdByKey = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> quotaByTenant = new ConcurrentHashMap<>();
    private final ReentrantLock[] stripes = new ReentrantLock[LOCK_STRIPES];

    @org.springframework.beans.factory.annotation.Autowired
    public EnvironmentService(RuntimeBackend backend, Clock clock, OrchestratorProperties props,
                              ReceiptStore receipts) {
        this.backend = backend;
        this.clock = clock;
        this.props = props;
        this.receipts = receipts;
        initLocks();
    }

    /** 便捷构造器：进程内自建回执存储（Spring 注入优先使用带 ReceiptStore 的构造器）。 */
    public EnvironmentService(RuntimeBackend backend, Clock clock, OrchestratorProperties props) {
        this(backend, clock, props, new ReceiptStore(clock));
    }

    private void initLocks() {
        for (int i = 0; i < LOCK_STRIPES; i++) {
            stripes[i] = new ReentrantLock();
        }
    }

    private ReentrantLock lockFor(String key) {
        return stripes[Math.floorMod(key.hashCode(), LOCK_STRIPES)];
    }

    // ---------------------------------------------------------------- 创建

    /** 写操作结果：环境快照 + 本次生效的请求标识（供调用方回查回执）。 */
    public record Outcome(EnvRecord record, String requestId) {
    }

    /**
     * 旧调用方式：不携带请求标识。
     * 有 idempotencyKey 时按键去重（向后兼容）；无键时两次创建是两个相互独立的环境。
     * 每次调用生成独立的回执请求标识（调用方未要求跨重试合并回执）。
     */
    public EnvRecord create(String tenantId, String idempotencyKey) {
        return createInternal(tenantId, resolveDedupKey(idempotencyKey), newRequestId()).record();
    }

    /**
     * 新调用方式：携带请求标识（X-Request-Id）。
     *
     * <p>环境去重键（dedupKey）与回执请求标识（requestId）解耦：
     * <ul>
     *   <li>显式给出 idempotencyKey：以其为环境去重键，同键合并到同一环境；</li>
     *   <li>未给 idempotencyKey：以 requestId 为环境去重键，同一标识的重试合并；</li>
     *   <li>requestId 为 null/空白：服务端每次生成唯一标识，去重键也唯一 -> 两个独立环境。</li>
     * </ul>
     */
    public Outcome createWithRequestId(String tenantId, String idempotencyKey, String clientRequestId) {
        boolean callerProvided = clientRequestId != null && !clientRequestId.isBlank();
        String requestId = callerProvided ? clientRequestId : newRequestId();
        // 显式幂等键优先；否则同请求标识的重试合并；无标识时每次唯一
        String dedupKey = (idempotencyKey != null && !idempotencyKey.isBlank())
                ? idempotencyKey
                : requestId;
        return createInternal(tenantId, dedupKey, requestId);
    }

    private Outcome createInternal(String tenantId, String dedupKey, String requestId) {
        // 第一步：按去重键原子“登记新环境 或 解析已有环境”。
        // 只有唯一进入新建分支的线程预留一份配额；键已存在的并发提交直接复用，不多占。
        // 必须先于回执占位，保证任何请求分支拿到时 env 都已登记，绝不会解析到 null。
        String mapKey = tenantId + ":" + dedupKey;
        final boolean[] quotaRejected = new boolean[1];
        final OrchestratorException[] quotaError = new OrchestratorException[1];
        String envId = envIdByKey.compute(mapKey, (k, priorEnvId) -> {
            if (priorEnvId != null) {
                return priorEnvId; // 键已存在：复用已有环境，不预留、不多占
            }
            try {
                reserveQuota(tenantId, requestId);
            } catch (OrchestratorException e) {
                // compute 内返回 null 即放弃本次写入，去重键不残留；原因在块外重抛
                quotaRejected[0] = true;
                quotaError[0] = e;
                return null;
            }
            EnvRecord record = EnvRecord.newCreating(tenantId, dedupKey, Instant.now(clock));
            byId.put(record.envId(), record);
            log.info("[{}] create registered env={} tenant={} state={} (quota reserved)",
                    requestId, record.envId(), tenantId, record.state());
            return record.envId();
        });
        if (quotaRejected[0]) {
            // 仅当回执占位成功才需要落 FAILED；此处尚在占位前，直接重抛（无回执记录）
            throw quotaError[0];
        }
        EnvRecord record = byIdOrThrow(envId);

        // 第二步：按请求标识占位回执，决定本次是否真正执行开通。
        ReceiptClaim claim = receipts.claim(requestId, tenantId, OperationType.CREATE);
        RequestReceipt receipt = claim.receipt();
        receipts.bindEnv(requestId, envId);
        if (!claim.acquired()) {
            // 命中历史结论：SUCCEEDED 复用同一环境（只开通一次）；PROCESSING 为同标识并发提交，
            // 另一个线程正在 env 锁内开通，这里返回快照、绝不二次开通。
            log.info("[{}] create idempotent-hit env={} tenant={} state={} receipt={} (no new instance)",
                    requestId, envId, tenantId, record.state(), receipt.status());
            return new Outcome(record, requestId);
        }
        // 占位成功（首次 或 上次 FAILED 的同标识重试）：若仍 CREATING 则开通，否则不重复变化。
        return new Outcome(provisionWithReceipt(requestId, record, receipt), requestId);
    }

    /** 若记录仍处 CREATING 则（重）试开通，并把成败写入回执；开通失败保持 CREATING 可再次重试。 */
    private EnvRecord provisionWithReceipt(String requestId, EnvRecord record, RequestReceipt receipt) {
        if (record.state() != EnvState.CREATING) {
            // 环境已开通完成：本次重复提交不产生任何变化，回执成功但 stateChanged=false
            receipts.succeeded(requestId, false, record.state(), record.state());
            return record;
        }
        try {
            EnvRecord stopped = doProvision(record, requestId);
            receipts.succeeded(requestId, true, EnvState.CREATING, EnvState.STOPPED);
            return stopped;
        } catch (OrchestratorException e) {
            // 开通失败：记录停留 CREATING、配额仍占用，回执 FAILED 可同标识安全重试
            receipts.failed(requestId, e.reason(), e.getMessage());
            throw e;
        }
    }

    private EnvRecord doProvision(EnvRecord record, String requestId) {
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
        return startWithRequestId(tenantId, envId, null).record();
    }

    public Outcome startWithRequestId(String tenantId, String envId, String clientRequestId) {
        String requestId = effectiveRequestId(clientRequestId);
        return operateWithReceipt(tenantId, envId, requestId, OperationType.START, this::doStart);
    }

    private EnvRecord doStart(EnvRecord current, String requestId) {
        requireStage(current, EnvState.STOPPED, OperationType.START);
        try {
            backend.start(current.envId());
        } catch (RuntimeException e) {
            throw backendFailure(requestId, current.envId(), "start", e);
        }
        return transitionLocked(current, EnvState.RUNNING, StopCause.NONE, requestId, "manual start");
    }

    public EnvRecord stop(String tenantId, String envId) {
        return stopWithRequestId(tenantId, envId, null).record();
    }

    public Outcome stopWithRequestId(String tenantId, String envId, String clientRequestId) {
        String requestId = effectiveRequestId(clientRequestId);
        return operateWithReceipt(tenantId, envId, requestId, OperationType.STOP,
                (cur, rid) -> doStop(cur, StopCause.MANUAL, rid));
    }

    private EnvRecord doStop(EnvRecord current, StopCause cause, String requestId) {
        requireStage(current, EnvState.RUNNING, cause == StopCause.MANUAL ? OperationType.STOP : null);
        try {
            backend.stop(current.envId());
        } catch (RuntimeException e) {
            throw backendFailure(requestId, current.envId(), "stop", e);
        }
        return transitionLocked(current, EnvState.STOPPED, cause, requestId,
                cause == StopCause.LEASE_EXPIRED ? "lease expired, auto stop" : "manual stop");
    }

    /**
     * 幂等销毁：非终态迁入 DESTROYING 并调用后端清理；后端失败保持 DESTROYING 可重试；
     * 已 DESTROYED 直接返回当前记录。配额在抵达 DESTROYED 时归还。
     */
    public EnvRecord destroy(String tenantId, String envId) {
        return destroyWithRequestId(tenantId, envId, null).record();
    }

    public Outcome destroyWithRequestId(String tenantId, String envId, String clientRequestId) {
        String requestId = effectiveRequestId(clientRequestId);
        return operateWithReceipt(tenantId, envId, requestId, OperationType.DESTROY,
                (cur, rid) -> doDestroy(cur, StopCause.MANUAL, rid));
    }

    private EnvRecord doDestroy(EnvRecord current, StopCause cause, String requestId) {
        ReentrantLock lock = lockFor(current.envId());
        lock.lock();
        try {
            EnvRecord fresh = byIdOrThrow(current.envId());
            if (fresh.state() == EnvState.DESTROYED) {
                log.info("[{}] destroy idempotent-hit env={} already DESTROYED", requestId, current.envId());
                return fresh;
            }
            EnvState from = fresh.state();
            // 允许 CREATING（开通失败的残留）、RUNNING、STOPPED、DESTROYING 进入/继续清理
            if (from != EnvState.DESTROYING && !Transitions.isAllowed(from, EnvState.DESTROYING)) {
                log.info("[{}] reject env={} tenant={} {}", requestId, current.envId(),
                        fresh.tenantId(), Transitions.explain(from, EnvState.DESTROYING));
                throw new OrchestratorException(FailureReason.ILLEGAL_STATE_TRANSITION,
                        Transitions.explain(from, EnvState.DESTROYING));
            }
            try {
                backend.destroy(current.envId()); // 后端幂等，同时清理开通失败可能留下的残留
            } catch (RuntimeException e) {
                put(fresh.withState(EnvState.DESTROYING, cause, now()));
                log.warn("[{}] destroy failed env={} {} -> DESTROYING kept, retryable: {}",
                        requestId, current.envId(), from, e.getMessage());
                throw new OrchestratorException(FailureReason.BACKEND_FAILURE,
                        "destroy failed for env " + current.envId() + ": " + e.getMessage());
            }
            EnvRecord destroying = from == EnvState.DESTROYING ? fresh
                    : put(fresh.withState(EnvState.DESTROYING, cause, now()));
            EnvRecord destroyed = transitionLocked(destroying, EnvState.DESTROYED, cause, requestId,
                    cause == StopCause.LEASE_EXPIRED ? "lease expired, auto destroy" : "manual destroy");
            releaseQuota(destroyed.tenantId(), requestId);
            return destroyed;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 面向调用方的实例级操作（start/stop/destroy）统一走回执：
     * 先做归属校验与回执占位，再在 env 分段锁内执行；命中历史结论直接复用，不重复执行后端。
     */
    @FunctionalInterface
    private interface EnvOperation {
        EnvRecord apply(EnvRecord current, String requestId);
    }

    private Outcome operateWithReceipt(String tenantId, String envId, String requestId,
                                       OperationType operation, EnvOperation op) {
        // 归属/存在性校验不挂回执：这是定位问题，与该请求操作的成败无关
        requireOwned(tenantId, envId, requestId);
        ReceiptClaim claim = receipts.claim(requestId, tenantId, operation);
        receipts.bindEnv(requestId, envId);
        if (!claim.acquired()) {
            // 命中历史结论：成功的重复提交不再调用后端、不重复改变状态；
            // 处理中的并发提交也复用同一次执行。两者都返回环境当前快照。
            return new Outcome(byIdOrThrow(envId), requestId);
        }
        EnvState from = byIdOrThrow(envId).state();
        try {
            EnvRecord result;
            ReentrantLock lock = lockFor(envId);
            lock.lock();
            try {
                result = op.apply(byIdOrThrow(envId), requestId);
            } finally {
                lock.unlock();
            }
            boolean changed = result.state() != from;
            receipts.succeeded(requestId, changed, from, result.state());
            return new Outcome(result, requestId);
        } catch (OrchestratorException e) {
            receipts.failed(requestId, e.reason(), e.getMessage());
            throw e;
        }
    }

    /**
     * 阶段前置校验：在调用后端实例操作之前判定该操作在当前阶段是否说得通。
     * 拒绝原因与普通非法迁移（ILLEGAL_STATE_TRANSITION）可区分，且绝不产生后端无实例、
     * 控制面却显示运行中的幻影状态。
     */
    private void requireStage(EnvRecord current, EnvState required, OperationType operation) {
        EnvState state = current.state();
        if (state == required) {
            return;
        }
        String op = operation == null ? "auto-stop" : operation.name().toLowerCase();
        if (state == EnvState.CREATING) {
            throw new OrchestratorException(FailureReason.ENV_NOT_PROVISIONED,
                    op + " rejected: env " + current.envId() + " is still CREATING (provision not complete); "
                            + "wait for provision to finish or retry create with the same request key");
        }
        if (state == EnvState.DESTROYING) {
            throw new OrchestratorException(FailureReason.ENV_TEARING_DOWN,
                    op + " rejected: env " + current.envId() + " is DESTROYING and cannot be " + op + "ped");
        }
        if (state == EnvState.DESTROYED) {
            throw new OrchestratorException(FailureReason.ILLEGAL_STATE_TRANSITION,
                    op + " rejected: env " + current.envId() + " is DESTROYED (terminal)");
        }
        // RUNNING/STOPPED 之间的错配（如对已停止再停止、对运行中再启动）：普通非法迁移
        throw new OrchestratorException(FailureReason.ILLEGAL_STATE_TRANSITION,
                Transitions.explain(state, operation == OperationType.START
                        ? EnvState.RUNNING : EnvState.STOPPED));
    }

    // ---------------------------------------------------------------- 回执查询

    /** 按请求标识回查回执（跨租户拒绝，不存在返回 REQUEST_NOT_FOUND）。 */
    public RequestReceipt getReceipt(String tenantId, String requestId) {
        return receipts.get(requestId, tenantId);
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
     * 由 LeaseReaper 调用：回收空闲超时环境（系统内部操作，不产生面向调用方的回执）。
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
                    stopInternal(r.envId(), StopCause.LEASE_EXPIRED, newRequestId());
                    reclaimed++;
                } else if (r.state() == EnvState.STOPPED
                        && r.lastActiveAt().plus(props.getIdleDestroyTtl()).isBefore(now)) {
                    destroyInternal(r.envId(), StopCause.LEASE_EXPIRED, newRequestId());
                    reclaimed++;
                } else if (r.state() == EnvState.DESTROYING) {
                    // 上次销毁中途失败的残留：每轮重试直至抵达终态，不遗漏
                    destroyInternal(r.envId(), r.stopCause(), newRequestId());
                    reclaimed++;
                }
            } catch (OrchestratorException e) {
                // 后端失败等：保持现状，下一轮重试，不遗漏
                log.warn("reap skipped env={} reason={} detail={}", r.envId(), e.reason(), e.getMessage());
            }
        }
        return reclaimed;
    }

    private void stopInternal(String envId, StopCause cause, String requestId) {
        ReentrantLock lock = lockFor(envId);
        lock.lock();
        try {
            EnvRecord current = byIdOrThrow(envId);
            doStop(current, cause, requestId);
        } finally {
            lock.unlock();
        }
    }

    private void destroyInternal(String envId, StopCause cause, String requestId) {
        EnvRecord current = byIdOrThrow(envId);
        doDestroy(current, cause, requestId);
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

    /** 调用方未提供有效标识时服务端生成一个（每次唯一），无标识创建因此各自独立。 */
    private String resolveDedupKey(String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            return idempotencyKey;
        }
        return "auto-" + UUID.randomUUID();
    }

    private String effectiveRequestId(String clientRequestId) {
        if (clientRequestId != null && !clientRequestId.isBlank()) {
            return clientRequestId;
        }
        return newRequestId();
    }

    private static String newRequestId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
