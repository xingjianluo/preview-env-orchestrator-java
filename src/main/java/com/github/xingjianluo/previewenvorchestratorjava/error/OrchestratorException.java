package com.github.xingjianluo.previewenvorchestratorjava.error;

/**
 * Business failure with a stable {@link ErrorCode} so callers can distinguish
 * failure causes.
 */
public class OrchestratorException extends RuntimeException {

    private final ErrorCode errorCode;

    public OrchestratorException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public OrchestratorException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
