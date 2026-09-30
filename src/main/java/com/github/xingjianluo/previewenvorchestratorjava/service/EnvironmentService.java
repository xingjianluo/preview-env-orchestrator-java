package com.github.xingjianluo.previewenvorchestratorjava.service;

import com.github.xingjianluo.previewenvorchestratorjava.backend.RuntimeBackend;
import com.github.xingjianluo.previewenvorchestratorjava.config.OrchestratorProperties;
import com.github.xingjianluo.previewenvorchestratorjava.domain.EnvRecord;
import com.github.xingjianluo.previewenvorchestratorjava.domain.EnvState;
import com.github.xingjianluo.previewenvorchestratorjava.domain.FailureReason;
import com.github.xingjianluo.previewenvorchestratorjava.domain.OperationKind;
import com.github.xingjianluo.previewenvorchestratorjava.domain.ReceiptStatus;
import com.github.xingjianluo.previewenvorchestratorjava.domain.StopCause;
import com.github.xingjianluo.previewenvorchestratorjava.domain.Transitions;
import com.github.xingjianluo.previewenvorchestratorjava.receipt.ReceiptStore;
import com.github.xingjianluo.previewenvorchestratorjava.receipt.RequestReceipt;
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
 * 编排控制面核心：生命周期状态机、创建区分、租户隔离、配额、租约回收与请求回执。
 *
 * 并发模型：
 * - 每条环境记录按 envId 分段加锁，迁移在锁内完成，杜绝并发覆盖；
 * - 带请求标识的创建按 (tenant, idempotencyKey) 在 ConcurrentHashMap.compute 内原子完成，
 *   同一 key 的重试/并发提交只会产生一条记录、开通一次、计一份配额；
 * - 不带请求标识的创建每次都登记新记录，同租户的两次创建彼此独立，互不顶替；
 * - 变更请求按 (tenant, requestId) 在 {@link ReceiptStore} 认领执行权：
 *   同标识并发/重试排队复用同一结论，成功结论冻结，后续操作不得改写；
 * - 配额使用 per-tenant AtomicInteger CAS 预留，销毁时归还，不超卖。
 */
@Service
public class EnvironmentService {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentService.class);

    private final RuntimeBackend backend;
    private final Clock clock;
    private final OrchestratorProperties props;
    private final ReceiptStore receipts;

    private final Map<String, EnvRecord> byId = new ConcurrentHashMap<>();
    private final Map<String, String> envIdByKey = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> quotaByTenant = new ConcurrentHashMap<>();
    /**
     * 每环境独立的锁对象：与请求回执的键空间完全隔离，
     * 不会因为 envId 与 requestId 的哈希碰撞而被无关请求占住。
     */
    private final Map<String, ReentrantLock> envLocks = new ConcurrentHashMap<>();
    /**
     * 每环境独立的“开通执行”锁：把后端 provision 串行成每个环境至多一次（失败除外）。
     * 它与状态迁移锁 {@link #envLocks} 分离：开通等待期间不持有迁移锁，阶段判定不被阻塞；
     * 但同一环境的并发开通会在此排队，拿到后 double-check，杜绝重复开通。
     */
    private final Map<String, ReentrantLock> provisionLocks = new ConcurrentHashMap<>();
    /**
     * 每幂等键一把锁：串行同 key 的创建，保证“同标识重试合并到同一环境、只开通一次、只占一份配额”。
     * 不同 key 使用不同锁对象，互不阻塞；无标识创建不经过这里。
     */
    private final Map<String, ReentrantLock> keyLocks = new ConcurrentHashMap<>();

    public EnvironmentService(RuntimeBackend backend, Clock clock, OrchestratorProperties props) {
        this.backend = backend;
        this.clock = clock;
        this.props = props;
        this.receipts = new ReceiptStore(clock);
    }

    private ReentrantLock lockFor(String envId) {
        return envLocks.computeIfAbsent(envId, id -> new ReentrantLock());
    }

    private ReentrantLock provisionLockFor(String envId) {
        return provisionLocks.computeIfAbsent(envId, id -> new ReentrantLock());
    }

    private ReentrantLock keyLockFor(String mapKey) {
        return keyLocks.computeIfAbsent(mapKey, k -> new ReentrantLock());
    }

    // ---------------------------------------------------------------- 创建

    /** 兼容旧签名：不带请求标识的调用方式照旧可用（本次创建仍会得到独立回执，可用返回的 requestId 回查）。 */
    public EnvRecord create(String tenantId, String idempotencyKey) {
        return create(tenantId, idempotencyKey, null).env();
    }

    /**
     * 创建环境。
     *
     * @param idempotencyKey 调用方请求标识：非空时同一 (tenant, key) 的重试合并到同一环境；
     *                       为 null 时每次调用都创建彼此独立的新环境，绝不按租户合并。
     * @param clientRequestId 回执用请求标识（X-Request-Id）；为 null 时服务端生成并随回执返回。
     */
    public OperationResult create(String tenantId, String idempotencyKey, String clientRequestId) {
        String requestId = clientRequestId != null ? clientRequestId : newRequestId();
        ReceiptStore.Claim claim = receipts.claim(tenantId, requestId, OperationKind.CREATE);
        RequestReceipt receipt = claim.receipt();
        if (claim.outcome() == ReceiptStore.ClaimOutcome.REPLAY) {
            return replay(tenantId, requestId, receipt);
        }
        try {
            EnvRecord registered;
            boolean isNewRegistration;
            if (idempotencyKey == null) {
                // 无请求标识：每次都是独立创建，绝不进入 envIdByKey，杜绝同租户互相顶替
                String envId = reserveAndRegister(tenantId, null, requestId);
                registered = byIdOrThrow(envId);
                isNewRegistration = true;
                EnvRecord result = ensureProvisioned(registered, requestId);
                boolean changed = result.state() != EnvState.CREATING;
                receipt = receipts.succeed(receipt, result.envId(), result.state(), changed);
                log.info("[{}] create settled env={} tenant={} state={} changed={}",
                        requestId, result.envId(), tenantId, result.state(), changed);
                return new OperationResult(result, receipt);
            }

            // 带请求标识：同 key 的并发/重试在专用键锁内串行，
            // 首个提交登记并开通，其余提交等待后拿到同一个 envId（不重复开通、不重复占配额）。
            String mapKey = tenantId + ":" + idempotencyKey;
            ReentrantLock keyLock = keyLockFor(mapKey);
            keyLock.lock();
            try {
                String existingEnvId = envIdByKey.get(mapKey);
                if (existingEnvId == null) {
                    String envId = reserveAndRegister(tenantId, idempotencyKey, requestId);
                    envIdByKey.put(mapKey, envId);
                    registered = byIdOrThrow(envId);
                    isNewRegistration = true;
                } else {
                    registered = byIdOrThrow(existingEnvId);
                    isNewRegistration = false;
                }
                EnvRecord result = ensureProvisioned(registered, requestId);
                boolean changed = isNewRegistration && result.state() != EnvState.CREATING;
                receipt = receipts.succeed(receipt, result.envId(), result.state(), changed);
                log.info("[{}] create settled env={} tenant={} state={} changed={}",
                        requestId, result.envId(), tenantId, result.state(), changed);
                return new OperationResult(result, receipt);
            } finally {
                keyLock.unlock();
            }
        } catch (OrchestratorException e) {
            throw failAndRethrow(receipt, e);
        }
    }

    private String reserveAndRegister(String tenantId, String idempotencyKey, String requestId) {
        reserveQuota(tenantId, requestId);
        EnvRecord record = EnvRecord.newCreating(tenantId, idempotencyKey, Instant.now(clock));
        byId.put(record.envId(), record);
        log.info("[{}] create registered env={} tenant={} state={} key={} (quota reserved)",
                requestId, record.envId(), tenantId, record.state(),
                idempotencyKey == null ? "<none>" : idempotencyKey);
        return record.envId();
    }

    /**
     * 若记录仍处 CREATING，则（重）试开通；开通失败保持 CREATING 可再次重试。
     *
     * <p>两把锁分离：
     * <ul>
     *   <li>{@code provisionLocks[env]} 串行后端开通本身（可能较慢），保证同一环境至多一个
     *       开通调用在途；等待它不阻塞阶段判定（start/stop 走另一把锁，看到 CREATING 立即拒绝）；</li>
     *   <li>{@code envLocks[env]} 只在提交 CREATING->STOPPED 迁移的瞬间持有并 double-check。</li>
     * </ul>
     * 后端 provision 幂等，即使失败重试也只落成一个实例。
     */
    private EnvRecord ensureProvisioned(EnvRecord record, String requestId) {
        if (record.state() != EnvState.CREATING) {
            log.info("[{}] create idempotent-hit env={} tenant={} state={} (no new instance)",
                    requestId, record.envId(), record.tenantId(), record.state());
            return record;
        }
        String envId = record.envId();
        ReentrantLock provisionLock = provisionLockFor(envId);
        provisionLock.lock();
        try {
            // double-check：可能在排队期间已被另一次并发重试开通
            EnvRecord latest = byIdOrThrow(envId);
            if (latest.state() != EnvState.CREATING) {
                log.info("[{}] provision skipped env={} already {} (concurrent retry won)",
                        requestId, envId, latest.state());
                return latest;
            }
            try {
                // 开通等待期间不持有状态迁移锁
                backend.provision(envId, latest.tenantId());
            } catch (RuntimeException e) {
                log.warn("[{}] provision failed env={} state stays CREATING, retryable: {}",
                        requestId, envId, e.getMessage());
                throw new OrchestratorException(FailureReason.BACKEND_FAILURE,
                        "provision failed for env " + envId + ": " + e.getMessage());
            }
        } finally {
            provisionLock.unlock();
        }
        ReentrantLock lock = lockFor(envId);
        lock.lock();
        try {
            EnvRecord current = byIdOrThrow(envId);
            if (current.state() != EnvState.CREATING) {
                return current;
            }
            return transitionLocked(current, EnvState.STOPPED, StopCause.NONE, requestId,
                    "provision succeeded");
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------- 状态迁移

    public EnvRecord start(String tenantId, String envId) {
        return start(tenantId, envId, null).env();
    }

    public OperationResult start(String tenantId, String envId, String clientRequestId) {
        return mutate(tenantId, envId, clientRequestId, OperationKind.START,
                (requestId, current) -> {
                    rejectWhileProvisioning(current, "start", requestId);
                    try {
                        backend.start(envId);
                    } catch (RuntimeException e) {
                        throw backendFailure(requestId, envId, "start", e);
                    }
                    return transitionLocked(current, EnvState.RUNNING, StopCause.NONE,
                            requestId, "manual start");
                });
    }

    public EnvRecord stop(String tenantId, String envId) {
        return stop(tenantId, envId, null).env();
    }

    public OperationResult stop(String tenantId, String envId, String clientRequestId) {
        return mutate(tenantId, envId, clientRequestId, OperationKind.STOP,
                (requestId, current) -> stopInternal(tenantId, envId, StopCause.MANUAL, requestId, current));
    }

    private EnvRecord stopInternal(String tenantId, String envId, StopCause cause, String requestId) {
        ReentrantLock lock = lockFor(envId);
        lock.lock();
        try {
            EnvRecord current = tenantId == null
                    ? byIdOrThrow(envId)
                    : requireOwned(tenantId, envId, requestId);
            return stopInternal(tenantId, envId, cause, requestId, current);
        } finally {
            lock.unlock();
        }
    }

    /** 停止主体：调用方已持锁或由公共 mutate 路径在锁内回调。 */
    private EnvRecord stopInternal(String tenantId, String envId, StopCause cause,
                                   String requestId, EnvRecord current) {
        if (tenantId != null) {
            // 公共手动停止：开通未完成阶段停止不成立，明确拒绝（租约回收不会遇到 CREATING）
            rejectWhileProvisioning(current, "stop", requestId);
        }
        try {
            backend.stop(envId);
        } catch (RuntimeException e) {
            throw backendFailure(requestId, envId, "stop", e);
        }
        return transitionLocked(current, EnvState.STOPPED, cause, requestId,
                cause == StopCause.LEASE_EXPIRED ? "lease expired, auto stop" : "manual stop");
    }

    public EnvRecord destroy(String tenantId, String envId) {
        return destroy(tenantId, envId, null).env();
    }

    public OperationResult destroy(String tenantId, String envId, String clientRequestId) {
        return mutate(tenantId, envId, clientRequestId, OperationKind.DESTROY,
                (requestId, current) -> destroyInternal(tenantId, envId, StopCause.MANUAL, requestId, current));
    }

    /** 环境单条锁内执行的变更动作，返回迁移后的最新记录。 */
    @FunctionalInterface
    private interface EnvMutation {
        EnvRecord apply(String requestId, EnvRecord current);
    }

    /**
     * start/stop/destroy 公共路径的统一包装：先按请求标识认领执行权，
     * 再在环境分段锁内完成鉴权与状态迁移，并落回执。
     */
    private OperationResult mutate(String tenantId, String envId, String clientRequestId,
                                   OperationKind kind, EnvMutation action) {
        String requestId = clientRequestId != null ? clientRequestId : newRequestId();
        ReceiptStore.Claim claim = receipts.claim(tenantId, requestId, kind);
        RequestReceipt receipt = claim.receipt();
        if (claim.outcome() == ReceiptStore.ClaimOutcome.REPLAY) {
            return replay(tenantId, requestId, receipt);
        }
        ReentrantLock lock = lockFor(envId);
        lock.lock();
        long beforeVersion;
        EnvRecord next;
        try {
            EnvRecord current = requireOwned(tenantId, envId, requestId);
            beforeVersion = current.version();
            next = action.apply(requestId, current);
        } catch (OrchestratorException e) {
            throw failAndRethrow(receipt, e);
        } finally {
            lock.unlock();
        }
        boolean changed = next.version() != beforeVersion;
        receipt = receipts.succeed(receipt, next.envId(), next.state(), changed);
        return new OperationResult(next, receipt);
    }

    /**
     * 同标识重放：不再次触碰后端。
     * - SUCCEEDED：返回冻结的成功结论（环境记录取当前值，状态以回执快照为准）；
     * - PROCESSING：上一次提交仍在途，返回处理中结论，调用方稍后回查；
     * - 冻结 FAILED：原样抛出，结论不被本次重写。
     */
    private OperationResult replay(String tenantId, String requestId, RequestReceipt receipt) {
        switch (receipt.status()) {
            case SUCCEEDED -> {
                EnvRecord current = requireOwned(tenantId, receipt.envId(), requestId);
                log.info("[{}] replay frozen {} env={} state={} changed={}",
                        requestId, receipt.kind(), receipt.envId(),
                        receipt.snapshotState(), receipt.stateChanged());
                return new OperationResult(current, receipt);
            }
            case PROCESSING -> {
                log.info("[{}] in-flight {} env={} — caller should poll receipt later",
                        requestId, receipt.kind(), receipt.envId());
                EnvRecord current = receipt.envId() == null ? null
                        : byId.get(receipt.envId());
                return new OperationResult(current, receipt);
            }
            case FAILED -> throw new OrchestratorException(
                    receipt.failureReason(), receipt.failureMessage(), requestId);
        }
        throw new IllegalStateException("unexpected receipt status for " + requestId);
    }

    /** 开通未完成阶段的显式拒绝：与普通非法迁移可区分。 */
    private void rejectWhileProvisioning(EnvRecord current, String op, String requestId) {
        if (current.state() == EnvState.CREATING) {
            String msg = op + " rejected: env " + current.envId()
                    + " is still CREATING (provisioning not finished); wait for provision to"
                    + " succeed or destroy it, then retry";
            log.info("[{}] reject env={} tenant={} reason=ENV_STILL_PROVISIONING op={}",
                    requestId, current.envId(), current.tenantId(), op);
            throw new OrchestratorException(FailureReason.ENV_STILL_PROVISIONING, msg);
        }
    }

    private EnvRecord destroyInternal(String tenantId, String envId, StopCause cause,
                                      String requestId, EnvRecord current) {
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
            put(current.withState(EnvState.DESTROYING, cause, now()));
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
    }

    /** 租约回收路径专用销毁（无租户校验、无回执）。 */
    private EnvRecord destroyInternal(String tenantId, String envId, StopCause cause, String requestId) {
        ReentrantLock lock = lockFor(envId);
        lock.lock();
        try {
            EnvRecord current = tenantId == null
                    ? byIdOrThrow(envId)
                    : requireOwned(tenantId, envId, requestId);
            return destroyInternal(tenantId, envId, cause, requestId, current);
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------- 回执查询

    /** 按请求标识回查回执；结论稳定，后续操作不会改写历史。不存在返回 RECEIPT_NOT_FOUND。 */
    public RequestReceipt getReceipt(String tenantId, String requestId) {
        return receipts.find(tenantId, requestId).orElseThrow(() -> {
            log.info("[{}] reject tenant={} reason=RECEIPT_NOT_FOUND", requestId, tenantId);
            return new OrchestratorException(FailureReason.RECEIPT_NOT_FOUND,
                    "no receipt found for request " + requestId);
        });
    }

    /** 失败落回执：判定是否可安全重试，随后原样抛出（携带 requestId）。 */
    private OrchestratorException failAndRethrow(RequestReceipt receipt, OrchestratorException e) {
        boolean retryable = switch (e.reason()) {
            // 时序/容量/后端类失败：环境未被改变，重试同样的请求是安全的
            case BACKEND_FAILURE, QUOTA_EXCEEDED, ENV_STILL_PROVISIONING,
                 ENV_NOT_FOUND, TENANT_FORBIDDEN -> true;
            // 阶段不成立（如已停止再停止）：同样的请求再发结果不变，不应盲目重试
            case ILLEGAL_STATE_TRANSITION -> false;
            // 认领阶段的异常不应走到这里
            case REQUEST_IN_PROGRESS, REQUEST_KIND_CONFLICT, RECEIPT_NOT_FOUND -> false;
        };
        receipts.fail(receipt, e.reason(), e.getMessage(), retryable);
        log.info("[{}] request failed reason={} retryable={} detail={}",
                receipt.requestId(), e.reason(), retryable, e.getMessage());
        return new OrchestratorException(e.reason(), e.getMessage(), receipt.requestId());
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
