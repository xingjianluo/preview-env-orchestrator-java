package com.github.xingjianluo.previewenvorchestratorjava.backend;

/**
 * Raised by a {@link RuntimeBackend} when a simulated instance operation
 * fails. The orchestrator translates this to a retryable BACKEND_FAILURE.
 */
public class BackendException extends RuntimeException {

    public BackendException(String message) {
        super(message);
    }
}
