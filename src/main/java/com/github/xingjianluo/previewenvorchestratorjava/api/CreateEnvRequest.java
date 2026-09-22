package com.github.xingjianluo.previewenvorchestratorjava.api;

public record CreateEnvRequest(String tenantId, String idempotencyKey) {
}
