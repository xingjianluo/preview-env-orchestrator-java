package com.github.xingjianluo.previewenvorchestratorjava.receipt;

import com.github.xingjianluo.previewenvorchestratorjava.domain.EnvState;
import com.github.xingjianluo.previewenvorchestratorjava.domain.FailureReason;
import com.github.xingjianluo.previewenvorchestratorjava.domain.OperationKind;
import com.github.xingjianluo.previewenvorchestratorjava.domain.ReceiptStatus;

import java.time.Instant;

/**
 * 一次变更请求的回执（不可变）。
 *
 * <p>语义：
 * <ul>
 *   <li>按 (tenantId, requestId) 唯一标识，反复回查结论稳定；</li>
 *   <li>终态（SUCCEEDED / 不可重试的 FAILED）一旦写入即冻结，
 *       之后该环境发生的别的操作不得改写本回执；</li>
 *   <li>可重试的 FAILED 允许持有同一 requestId 的调用方继续重试，
 *       成功后收敛为 SUCCEEDED；</li>
 *   <li>{@code stateChanged} 表示本次请求是否真的推动了一次状态迁移
 *       （幂等命中返回既有结论时为 false）；</li>
 *   <li>{@code snapshotState} 是结论产生那一刻环境所处状态，随回执冻结。</li>
 * </ul>
 */
public record RequestReceipt(
        String tenantId,
        String requestId,
        OperationKind kind,
        ReceiptStatus status,
        boolean retryable,
        String envId,
        EnvState snapshotState,
        boolean stateChanged,
        FailureReason failureReason,
        String failureMessage,
        Instant createdAt,
        Instant finishedAt) {

    public static RequestReceipt processing(String tenantId, String requestId, OperationKind kind, Instant now) {
        return new RequestReceipt(tenantId, requestId, kind, ReceiptStatus.PROCESSING, false,
                null, null, false, null, null, now, null);
    }

    public RequestReceipt succeeded(String envId, EnvState state, boolean changed, Instant now) {
        return new RequestReceipt(tenantId, requestId, kind, ReceiptStatus.SUCCEEDED, false,
                envId, state, changed, null, null, createdAt, now);
    }

    public RequestReceipt failed(FailureReason reason, String message, boolean retryable, Instant now) {
        return new RequestReceipt(tenantId, requestId, kind, ReceiptStatus.FAILED, retryable,
                envId, snapshotState, false, reason, message, createdAt, now);
    }

    /** 是否仍在处理中。 */
    public boolean isProcessing() {
        return status == ReceiptStatus.PROCESSING;
    }

    /** 是否为不可变终态结论。 */
    public boolean isTerminal() {
        return status == ReceiptStatus.SUCCEEDED || (status == ReceiptStatus.FAILED && !retryable);
    }
}
