package com.github.xingjianluo.previewenvorchestratorjava.api;

import com.github.xingjianluo.previewenvorchestratorjava.domain.CreateEnvironmentRequest;
import com.github.xingjianluo.previewenvorchestratorjava.domain.PreviewEnvironment;
import com.github.xingjianluo.previewenvorchestratorjava.error.ErrorCode;
import com.github.xingjianluo.previewenvorchestratorjava.error.OrchestratorException;
import com.github.xingjianluo.previewenvorchestratorjava.service.EnvironmentOrchestrator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST surface of the preview-environment control plane.
 *
 * <p>Every mutating endpoint requires an {@code Idempotency-Key} header;
 * submitting the same key replays the original outcome instead of creating a
 * second instance.
 */
@RestController
@RequestMapping("/api/tenants/{tenantId}/environments")
public class EnvironmentController {

    private final EnvironmentOrchestrator orchestrator;

    public EnvironmentController(EnvironmentOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping
    public ResponseEntity<Dtos.EnvironmentResponse> create(
            @PathVariable String tenantId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Dtos.CreateEnvironmentRequest body) {
        String key = idempotencyKey != null ? idempotencyKey
                : (body == null ? null : body.idempotencyKey());
        if (body == null) {
            throw new OrchestratorException(ErrorCode.INVALID_REQUEST, "request body required");
        }
        PreviewEnvironment env = orchestrator.createEnvironment(
                tenantId, key, new CreateEnvironmentRequest(body.image()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Dtos.EnvironmentResponse.from(env));
    }

    @GetMapping
    public List<Dtos.EnvironmentResponse> list(@PathVariable String tenantId) {
        return orchestrator.list(tenantId).stream()
                .map(Dtos.EnvironmentResponse::from)
                .toList();
    }

    @GetMapping("/{envId}")
    public Dtos.EnvironmentResponse get(@PathVariable String tenantId,
                                        @PathVariable String envId) {
        return orchestrator.get(tenantId, envId)
                .map(Dtos.EnvironmentResponse::from)
                .orElseThrow(() -> new OrchestratorException(ErrorCode.ENV_NOT_FOUND,
                        "environment not found: " + envId));
    }

    @PostMapping("/{envId}/start")
    public Dtos.EnvironmentResponse start(
            @PathVariable String tenantId,
            @PathVariable String envId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return Dtos.EnvironmentResponse.from(orchestrator.start(tenantId, envId, idempotencyKey));
    }

    @PostMapping("/{envId}/stop")
    public Dtos.EnvironmentResponse stop(
            @PathVariable String tenantId,
            @PathVariable String envId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return Dtos.EnvironmentResponse.from(orchestrator.stop(tenantId, envId, idempotencyKey));
    }

    @PostMapping("/{envId}/destroy")
    public Dtos.EnvironmentResponse destroy(
            @PathVariable String tenantId,
            @PathVariable String envId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return Dtos.EnvironmentResponse.from(orchestrator.destroy(tenantId, envId, idempotencyKey));
    }
}
