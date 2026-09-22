package com.github.xingjianluo.previewenvorchestratorjava.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 环境实例的控制面记录（不可变快照语义：所有字段在迁移时整体替换）。
 */
public final class EnvRecord {

    private final String envId;
    private final String tenantId;
    private final String idempotencyKey;
    private final EnvState state;
    private final StopCause stopCause;
    private final Instant createdAt;
    private final Instant lastActiveAt;
    private final long version;

    public EnvRecord(String envId, String tenantId, String idempotencyKey, EnvState state,
                     StopCause stopCause, Instant createdAt, Instant lastActiveAt, long version) {
        this.envId = envId;
        this.tenantId = tenantId;
        this.idempotencyKey = idempotencyKey;
        this.state = state;
        this.stopCause = stopCause;
        this.createdAt = createdAt;
        this.lastActiveAt = lastActiveAt;
        this.version = version;
    }

    public static EnvRecord newCreating(String tenantId, String idempotencyKey, Instant now) {
        return new EnvRecord(UUID.randomUUID().toString(), tenantId, idempotencyKey,
                EnvState.CREATING, StopCause.NONE, now, now, 0L);
    }

    public EnvRecord withState(EnvState newState, StopCause cause, Instant now) {
        return new EnvRecord(envId, tenantId, idempotencyKey, newState, cause, createdAt, now, version + 1);
    }

    public EnvRecord touch(Instant now) {
        return new EnvRecord(envId, tenantId, idempotencyKey, state, stopCause, createdAt, now, version + 1);
    }

    public String envId() { return envId; }
    public String tenantId() { return tenantId; }
    public String idempotencyKey() { return idempotencyKey; }
    public EnvState state() { return state; }
    public StopCause stopCause() { return stopCause; }
    public Instant createdAt() { return createdAt; }
    public Instant lastActiveAt() { return lastActiveAt; }
    public long version() { return version; }
}
