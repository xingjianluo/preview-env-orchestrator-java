package com.github.xingjianluo.previewenvorchestratorjava.api;

import com.github.xingjianluo.previewenvorchestratorjava.domain.EnvRecord;

import java.time.Instant;

public record EnvDto(String envId, String tenantId, String state, String stopCause,
                     Instant lastActiveAt, long version) {

    public static EnvDto from(EnvRecord r) {
        return new EnvDto(r.envId(), r.tenantId(), r.state().name(), r.stopCause().name(),
                r.lastActiveAt(), r.version());
    }
}
