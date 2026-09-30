package com.github.xingjianluo.previewenvorchestratorjava.domain;

import java.time.Instant;

/**
 * 一次写操作（创建/启动/停止/销毁）的请求回执（不可变快照，状态推进时整体替换）。
 *
 * 语义：
 * - PROCESSING：首次受理；同一请求标识的重复提交会复用本回执（重试合并）。
 * - SUCCEEDED：终态，结论稳定，后续任何操作都不得再改写；记录作用的环境、是否真的改变状态及迁移前后状态。
 * - FAILED：调用方可使用同一请求标识安全重试，重试成功后推进为 SUCCEEDED。
 */
public final class RequestReceipt {

    private final String requestId;
    private final String tenantId;
    private final OperationType operation;
    private final String envId;
    private final RequestStatus status;
    private final FailureReason failureReason;
    private final String message;
    private final boolean stateChanged;
    private final EnvState fromState;
    private final EnvState toState;
    private final Instant createdAt;
    private final Instant updatedAt;

    public RequestReceipt(String requestId, String tenantId, OperationType operation, String envId,
                          RequestStatus status, FailureReason failureReason, String message,
                          boolean stateChanged, EnvState fromState, EnvState toState,
                          Instant createdAt, Instant updatedAt) {
        this.requestId = requestId;
        this.tenantId = tenantId;
        this.operation = operation;
        this.envId = envId;
        this.status = status;
        this.failureReason = failureReason;
        this.message = message;
        this.stateChanged = stateChanged;
        this.fromState = fromState;
        this.toState = toState;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** 首次受理：处理中，尚无作用环境（创建场景注册前）。 */
    public static RequestReceipt processing(String requestId, String tenantId, OperationType operation,
                                            String envId, Instant now) {
        return new RequestReceipt(requestId, tenantId, operation, envId, RequestStatus.PROCESSING,
                null, null, false, null, null, now, now);
    }

    /** 回执已存在但作用环境尚未绑定（创建注册成功后）时补齐 envId。 */
    public RequestReceipt bindEnv(String boundEnvId, Instant now) {
        return new RequestReceipt(requestId, tenantId, operation, boundEnvId, status, failureReason,
                message, stateChanged, fromState, toState, createdAt, now);
    }

    public RequestReceipt succeeded(boolean changed, EnvState from, EnvState to, Instant now) {
        return new RequestReceipt(requestId, tenantId, operation, envId, RequestStatus.SUCCEEDED,
                null, null, changed, from, to, createdAt, now);
    }

    public RequestReceipt failed(FailureReason reason, String detail, Instant now) {
        return new RequestReceipt(requestId, tenantId, operation, envId, RequestStatus.FAILED,
                reason, detail, stateChanged, fromState, toState, createdAt, now);
    }

    public String requestId() { return requestId; }
    public String tenantId() { return tenantId; }
    public OperationType operation() { return operation; }
    public String envId() { return envId; }
    public RequestStatus status() { return status; }
    public FailureReason failureReason() { return failureReason; }
    public String message() { return message; }
    public boolean stateChanged() { return stateChanged; }
    public EnvState fromState() { return fromState; }
    public EnvState toState() { return toState; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
