package com.github.xingjianluo.previewenvorchestratorjava.error;

/**
 * Distinguishable failure reasons for rejected operations.
 */
public enum ErrorCode {
    ENV_NOT_FOUND,
    TENANT_MISMATCH,
    INVALID_STATE_TRANSITION,
    QUOTA_EXCEEDED,
    IDEMPOTENCY_CONFLICT,
    INVALID_REQUEST,
    BACKEND_FAILURE,
    CONFLICT
}
