package com.github.xingjianluo.previewenvorchestratorjava.domain;

/**
 * 可区分的失败原因。
 */
public enum FailureReason {
    ENV_NOT_FOUND,
    TENANT_FORBIDDEN,
    QUOTA_EXCEEDED,
    ILLEGAL_STATE_TRANSITION,
    BACKEND_FAILURE
}
