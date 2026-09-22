package com.github.xingjianluo.previewenvorchestratorjava.store;

import com.github.xingjianluo.previewenvorchestratorjava.domain.PreviewEnvironment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory storage of environments keyed by environment id.
 *
 * <p>The map itself is thread-safe; mutations of an individual
 * {@link PreviewEnvironment} are serialized by the orchestrator's
 * per-environment locks.
 */
@Component
public class EnvironmentRepository {

    private final ConcurrentMap<String, PreviewEnvironment> envs = new ConcurrentHashMap<>();

    /** Insert a new environment. Fails if the id is already present. */
    public void save(PreviewEnvironment env) {
        PreviewEnvironment previous = envs.putIfAbsent(env.getEnvId(), env);
        if (previous != null) {
            throw new IllegalStateException("environment already exists: " + env.getEnvId());
        }
    }

    public Optional<PreviewEnvironment> findById(String envId) {
        return Optional.ofNullable(envs.get(envId));
    }

    public List<PreviewEnvironment> findByTenant(String tenantId) {
        return envs.values().stream()
                .filter(e -> e.getTenantId().equals(tenantId))
                .toList();
    }

    /** Snapshot of all environments; safe for the reaper to iterate. */
    public List<PreviewEnvironment> findAll() {
        return List.copyOf(envs.values());
    }
}
