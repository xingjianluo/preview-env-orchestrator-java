package com.github.xingjianluo.previewenvorchestratorjava.idem;

/**
 * Result stored under an idempotency key once an operation has finished.
 */
public record IdempotencyOutcome(boolean success, String resourceId, String errorCode) {

    public static IdempotencyOutcome ok(String resourceId) {
        return new IdempotencyOutcome(true, resourceId, null);
    }

    public static IdempotencyOutcome failure(String errorCode) {
        return new IdempotencyOutcome(false, null, errorCode);
    }
}
