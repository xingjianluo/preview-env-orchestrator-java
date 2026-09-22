package com.github.xingjianluo.previewenvorchestratorjava.api;

import com.github.xingjianluo.previewenvorchestratorjava.config.RequestIdFilter;
import com.github.xingjianluo.previewenvorchestratorjava.error.ErrorCode;
import com.github.xingjianluo.previewenvorchestratorjava.error.OrchestratorException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates business failures into structured, distinguishable HTTP
 * responses. The error code and request id let callers safely branch on the
 * failure cause and correlate retries.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(OrchestratorException.class)
    public ResponseEntity<Dtos.ErrorResponse> handleBusiness(OrchestratorException ex) {
        HttpStatus status = httpStatus(ex.getErrorCode());
        Dtos.ErrorResponse body = new Dtos.ErrorResponse(
                MDC.get(RequestIdFilter.MDC_KEY),
                ex.getErrorCode().name(),
                ex.getMessage());
        return ResponseEntity.status(status).body(body);
    }

    private HttpStatus httpStatus(ErrorCode code) {
        return switch (code) {
            case ENV_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case TENANT_MISMATCH -> HttpStatus.FORBIDDEN;
            case INVALID_STATE_TRANSITION, IDEMPOTENCY_CONFLICT, CONFLICT -> HttpStatus.CONFLICT;
            case QUOTA_EXCEEDED -> HttpStatus.TOO_MANY_REQUESTS;
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case BACKEND_FAILURE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }
}
