package com.github.xingjianluo.previewenvorchestratorjava.api;

public record ErrorResponse(String reason, String message, String requestId) {

    public ErrorResponse(String reason, String message) {
        this(reason, message, null);
    }
}
