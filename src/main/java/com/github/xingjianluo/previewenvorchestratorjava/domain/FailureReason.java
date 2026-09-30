package com.github.xingjianluo.previewenvorchestratorjava.domain;

/**
 * 可区分的失败原因。
 */
public enum FailureReason {
    ENV_NOT_FOUND,
    TENANT_FORBIDDEN,
    QUOTA_EXCEEDED,
    ILLEGAL_STATE_TRANSITION,
    BACKEND_FAILURE,
    /** 环境仍处于开通阶段（CREATING），启动/停止在该阶段不成立。 */
    ENV_STILL_PROVISIONING,
    /** 同一请求标识的上一次提交仍在处理中，尚无结论，需稍后回查。 */
    REQUEST_IN_PROGRESS,
    /** 请求标识已被另一类操作占用（如同一 X-Request-Id 先 stop 又 create）。 */
    REQUEST_KIND_CONFLICT,
    /** 回查的请求标识在本租户下不存在任何回执。 */
    RECEIPT_NOT_FOUND
}
