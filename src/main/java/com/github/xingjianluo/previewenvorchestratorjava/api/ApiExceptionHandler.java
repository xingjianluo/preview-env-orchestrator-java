package com.github.xingjianluo.previewenvorchestratorjava.api;

import com.github.xingjianluo.previewenvorchestratorjava.domain.FailureReason;
import com.github.xingjianluo.previewenvorchestratorjava.service.OrchestratorException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 将编排异常映射为可区分的 HTTP 状态与失败原因。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(OrchestratorException.class)
    public ResponseEntity<ErrorResponse> handle(OrchestratorException e) {
        HttpStatus status = switch (e.reason()) {
            case ENV_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case TENANT_FORBIDDEN -> HttpStatus.FORBIDDEN;
            case QUOTA_EXCEEDED -> HttpStatus.TOO_MANY_REQUESTS;
            case ILLEGAL_STATE_TRANSITION -> HttpStatus.CONFLICT;
            case BACKEND_FAILURE -> HttpStatus.BAD_GATEWAY;
        };
        return ResponseEntity.status(status)
                .body(new ErrorResponse(e.reason().name(), e.getMessage()));
    }
}
