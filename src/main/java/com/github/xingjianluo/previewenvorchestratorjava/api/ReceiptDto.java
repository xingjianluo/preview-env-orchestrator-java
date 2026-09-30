package com.github.xingjianluo.previewenvorchestratorjava.api;

import com.github.xingjianluo.previewenvorchestratorjava.receipt.RequestReceipt;

import java.time.Instant;

/**
 * 请求回执视图：结论、作用环境、是否真正改变状态。
 * 同一请求标识反复回查返回稳定结论（快照字段在结论产生时冻结）。
 */
public record ReceiptDto(
        String requestId,
        String operation,
        String status,
        boolean retryable,
        String envId,
        String snapshotState,
        boolean stateChanged,
        String failureReason,
        String failureMessage,
        Instant createdAt,
        Instant finishedAt) {

    public static ReceiptDto from(RequestReceipt r) {
        return new ReceiptDto(
                r.requestId(),
                r.kind().name(),
                r.status().name(),
                r.retryable(),
                r.envId(),
                r.snapshotState() == null ? null : r.snapshotState().name(),
                r.stateChanged(),
                r.failureReason() == null ? null : r.failureReason().name(),
                r.failureMessage(),
                r.createdAt(),
                r.finishedAt());
    }
}
