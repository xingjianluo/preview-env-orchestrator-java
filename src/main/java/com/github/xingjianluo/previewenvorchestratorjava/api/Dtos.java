package com.github.xingjianluo.previewenvorchestratorjava.api;

import com.github.xingjianluo.previewenvorchestratorjava.domain.PreviewEnvironment;

import java.util.List;

/**
 * REST DTOs.
 */
public final class Dtos {

    private Dtos() {
    }

    public record CreateEnvironmentRequest(String image, String idempotencyKey) {
    }

    public record EnvironmentResponse(
            String envId,
            String tenantId,
            String state,
            String lastReason,
            long createdAt,
            long lastActivityAt,
            long stoppedAt,
            List<TransitionView> transitions
    ) {
        static EnvironmentResponse from(PreviewEnvironment e) {
            return new EnvironmentResponse(
                    e.getEnvId(),
                    e.getTenantId(),
                    e.getState().name(),
                    e.lastReason() == null ? null : e.lastReason().name(),
                    e.getCreatedAt(),
                    e.getLastActivityAt(),
                    e.getStoppedAt(),
                    e.getTransitions().stream().map(TransitionView::from).toList());
        }
    }

    public record TransitionView(String from, String to, String reason, long atMillis) {
        static TransitionView from(com.github.xingjianluo.previewenvorchestratorjava.domain.StateTransition t) {
            return new TransitionView(
                    t.from() == null ? null : t.from().name(),
                    t.to().name(),
                    t.reason().name(),
                    t.atMillis());
        }
    }

    public record ErrorResponse(String requestId, String errorCode, String message) {
    }
}
