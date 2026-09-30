package com.github.xingjianluo.previewenvorchestratorjava.api;

import com.github.xingjianluo.previewenvorchestratorjava.domain.RequestReceipt;

import java.time.Instant;

/**
 * 请求回执视图：本次请求的最终结论、作用环境、是否真的改变状态及迁移前后状态。
 */
public record ReceiptDto(String requestId, String tenantId, String operation, String envId,
                         String status, String failureReason, String message,
                         boolean stateChanged, String fromState, String toState,
                         Instant createdAt, Instant updatedAt) {

    public static ReceiptDto from(RequestReceipt r) {
        return new ReceiptDto(
                r.requestId(),
                r.tenantId(),
                r.operation() == null ? null : r.operation().name(),
                r.envId(),
                r.status().name(),
                r.failureReason() == null ? null : r.failureReason().name(),
                r.message(),
                r.stateChanged(),
                r.fromState() == null ? null : r.fromState().name(),
                r.toState() == null ? null : r.toState().name(),
                r.createdAt(),
                r.updatedAt());
    }
}
