package com.github.xingjianluo.previewenvorchestratorjava.service;

import com.github.xingjianluo.previewenvorchestratorjava.domain.FailureReason;

/**
 * 编排层异常：携带可区分的失败原因与判定依据。
 */
public class OrchestratorException extends RuntimeException {

    private final FailureReason reason;

    public OrchestratorException(FailureReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public FailureReason reason() {
        return reason;
    }
}
