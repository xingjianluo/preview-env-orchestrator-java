package com.github.xingjianluo.previewenvorchestratorjava.domain;

/**
 * 可区分的失败原因。
 */
public enum FailureReason {
    ENV_NOT_FOUND,
    TENANT_FORBIDDEN,
    QUOTA_EXCEEDED,
    ILLEGAL_STATE_TRANSITION,
    /** 环境尚未开通完成（CREATING），启动/停止等实例级操作在此阶段说不通，被前置拒绝。 */
    ENV_NOT_PROVISIONED,
    /** 环境已在销毁流程中（DESTROYING），不允许再启动/停止。 */
    ENV_TEARING_DOWN,
    /** 请求回执查询时未找到该请求标识。 */
    REQUEST_NOT_FOUND,
    /** 同一请求标识被复用到与首次不同类型的操作，拒绝以免串改历史结论。 */
    REQUEST_CONFLICT,
    BACKEND_FAILURE
}
