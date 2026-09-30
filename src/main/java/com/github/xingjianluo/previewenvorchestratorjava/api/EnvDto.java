package com.github.xingjianluo.previewenvorchestratorjava.api;

import com.github.xingjianluo.previewenvorchestratorjava.domain.EnvRecord;

import java.time.Instant;

public record EnvDto(String envId, String tenantId, String state, String stopCause,
                     Instant lastActiveAt, long version, String requestId) {

    /** 旧调用方式（无显式请求标识）：requestId 为服务端当次生成的回执标识。 */
    public static EnvDto from(EnvRecord r) {
        return new EnvDto(r.envId(), r.tenantId(), r.state().name(), r.stopCause().name(),
                r.lastActiveAt(), r.version(), null);
    }

    public static EnvDto from(EnvRecord r, String requestId) {
        return new EnvDto(r.envId(), r.tenantId(), r.state().name(), r.stopCause().name(),
                r.lastActiveAt(), r.version(), requestId);
    }
}
