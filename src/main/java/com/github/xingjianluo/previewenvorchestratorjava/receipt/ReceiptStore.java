package com.github.xingjianluo.previewenvorchestratorjava.receipt;

import com.github.xingjianluo.previewenvorchestratorjava.domain.EnvState;
import com.github.xingjianluo.previewenvorchestratorjava.domain.FailureReason;
import com.github.xingjianluo.previewenvorchestratorjava.domain.OperationKind;
import com.github.xingjianluo.previewenvorchestratorjava.domain.ReceiptStatus;
import com.github.xingjianluo.previewenvorchestratorjava.service.OrchestratorException;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 请求回执存储：按 (tenantId, requestId) 维护每次变更请求的结论。
 *
 * <p>认领语义（{@link #claim}）：
 * <ul>
 *   <li>无记录：原子写入 PROCESSING，当前调用获得执行权（EXECUTE）；</li>
 *   <li>已有 PROCESSING：同一请求标识的并发提交立即得到 PROCESSING 结论（REPLAY），
 *       不阻塞、不重复执行后端调用，调用方稍后用同一标识回查即可；</li>
 *   <li>已有 SUCCEEDED：冻结结论原样返回（REPLAY），不再执行任何后端调用；</li>
 *   <li>已有可重试 FAILED：单个提交者把回执重新置为 PROCESSING 获得执行权重试，
 *       成功后结论覆盖为 SUCCEEDED；同一时刻其它提交仍只看到 PROCESSING；</li>
 *   <li>已有不可重试 FAILED：冻结失败原样返回；</li>
 *   <li>同一 requestId 被不同操作类型复用：REQUEST_KIND_CONFLICT。</li>
 * </ul>
 */
public class ReceiptStore {

    /** 认领结果：EXECUTE 获得执行权；REPLAY 返回既有结论。 */
    public enum ClaimOutcome { EXECUTE, REPLAY }

    public record Claim(ClaimOutcome outcome, RequestReceipt receipt) {
    }

    private final Map<String, RequestReceipt> byKey = new ConcurrentHashMap<>();
    private final Clock clock;

    public ReceiptStore(Clock clock) {
        this.clock = clock;
    }

    private static String key(String tenantId, String requestId) {
        return tenantId + "\u0000" + requestId;
    }

    /**
     * 认领本次请求（非阻塞）。同一 (tenant, requestId) 的结论在此原子裁决。
     */
    public Claim claim(String tenantId, String requestId, OperationKind kind) {
        Claim[] holder = new Claim[1];
        byKey.compute(key(tenantId, requestId), (k, existing) -> {
            Instant now = Instant.now(clock);
            if (existing == null) {
                RequestReceipt r = RequestReceipt.processing(tenantId, requestId, kind, now);
                holder[0] = new Claim(ClaimOutcome.EXECUTE, r);
                return r;
            }
            if (existing.kind() != kind) {
                throw new OrchestratorException(FailureReason.REQUEST_KIND_CONFLICT,
                        "request id " + requestId + " is already bound to operation "
                                + existing.kind() + ", cannot reuse for " + kind, requestId);
            }
            switch (existing.status()) {
                case PROCESSING -> {
                    // 在途：非阻塞返回当前结论，调用方稍后回查；绝不重复执行
                    holder[0] = new Claim(ClaimOutcome.REPLAY, existing);
                    return existing;
                }
                case SUCCEEDED -> {
                    holder[0] = new Claim(ClaimOutcome.REPLAY, existing);
                    return existing;
                }
                case FAILED -> {
                    if (!existing.retryable()) {
                        holder[0] = new Claim(ClaimOutcome.REPLAY, existing);
                        return existing;
                    }
                    // 可重试失败：原子重开，仅一个提交者拿到执行权；
                    // 沿用上次已登记的 envId，重试复用同一环境（不新建、不重复占配额）
                    RequestReceipt reopened = RequestReceipt.processing(tenantId, requestId, kind, now)
                            .withEnv(existing.envId());
                    holder[0] = new Claim(ClaimOutcome.EXECUTE, reopened);
                    return reopened;
                }
            }
            throw new IllegalStateException("unexpected receipt status for " + requestId);
        });
        return holder[0];
    }

    /** 写入成功结论；仅当当前回执仍为 PROCESSING 时生效（防御性）。 */
    public RequestReceipt succeed(RequestReceipt processing, String envId,
                                  EnvState snapshotState, boolean changed) {
        String k = key(processing.tenantId(), processing.requestId());
        RequestReceipt updated = processing.succeeded(envId, snapshotState, changed, Instant.now(clock));
        byKey.compute(k, (key, current) ->
                current != null && current.status() == ReceiptStatus.PROCESSING ? updated : current);
        return byKey.get(k);
    }

    /**
     * 把已登记的环境绑定到在途（PROCESSING）回执上。
     * 创建请求登记环境后立即调用：此后即使开通失败，FAILED 回执仍携带 envId，
     * 同一请求标识的重试可据此找回同一个环境。
     */
    public RequestReceipt bindEnv(RequestReceipt processing, String envId) {
        String k = key(processing.tenantId(), processing.requestId());
        RequestReceipt updated = processing.withEnv(envId);
        byKey.compute(k, (key, current) ->
                current != null && current.status() == ReceiptStatus.PROCESSING ? updated : current);
        return byKey.get(k);
    }

    /** 写入失败结论；可重试失败允许后续同标识提交继续重试。 */
    public RequestReceipt fail(RequestReceipt processing, FailureReason reason,
                               String message, boolean retryable) {
        String k = key(processing.tenantId(), processing.requestId());
        RequestReceipt updated = processing.failed(reason, message, retryable, Instant.now(clock));
        byKey.compute(k, (key, current) ->
                current != null && current.status() == ReceiptStatus.PROCESSING ? updated : current);
        return byKey.get(k);
    }

    public Optional<RequestReceipt> find(String tenantId, String requestId) {
        return Optional.ofNullable(byKey.get(key(tenantId, requestId)));
    }
}
