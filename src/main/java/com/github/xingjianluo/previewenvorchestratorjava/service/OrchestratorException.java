package com.github.xingjianluo.previewenvorchestratorjava.service;

import com.github.xingjianluo.previewenvorchestratorjava.domain.FailureReason;

/**
 * 编排层异常：携带可区分的失败原因与判定依据。
 */
public class OrchestratorException extends RuntimeException {

    private final FailureReason reason;
    private final String requestId;

    public OrchestratorException(FailureReason reason, String message) {
        this(reason, message, null);
    }

    public OrchestratorException(FailureReason reason, String message, String requestId) {
        super(message);
        this.reason = reason;
        this.requestId = requestId;
    }

    public FailureReason reason() {
        return reason;
    }

    public String requestId() {
        return requestId;
    }
}
