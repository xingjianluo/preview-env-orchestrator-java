package com.github.xingjianluo.previewenvorchestratorjava.quota;

import com.github.xingjianluo.previewenvorchestratorjava.error.ErrorCode;
import com.github.xingjianluo.previewenvorchestratorjava.error.OrchestratorException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-tenant capacity accounting.
 *
 * <p>A slot is reserved atomically when creation is accepted and released
 * exactly once when the environment reaches TERMINATED. Reservation uses
 * compare-and-set so that concurrent create requests can never oversell:
 * under contention the losers observe {@link ErrorCode#QUOTA_EXCEEDED}.
 */
@Component
public class QuotaService {

    @Value("${orchestrator.quota.default:2}")
    private volatile int defaultQuota;

    private final ConcurrentMap<String, AtomicInteger> usedByTenant = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Integer> quotaOverrides = new ConcurrentHashMap<>();

    /** Atomically reserve one slot for the tenant. Throws on exhaustion. */
    public void reserve(String tenantId) {
        AtomicInteger used = usedByTenant.computeIfAbsent(tenantId, k -> new AtomicInteger());
        int limit = quota(tenantId);
        while (true) {
            int current = used.get();
            if (current >= limit) {
                throw new OrchestratorException(ErrorCode.QUOTA_EXCEEDED,
                        "tenant " + tenantId + " quota exhausted: used " + current
                                + ", limit " + limit);
            }
            if (used.compareAndSet(current, current + 1)) {
                return;
            }
        }
    }

    /**
     * Return one reserved slot. Called exactly once per successful
     * reservation (after the environment is fully terminated), so a failed or
     * retried create/destroy can never double-release.
     */
    public void release(String tenantId) {
        AtomicInteger used = usedByTenant.computeIfAbsent(tenantId, k -> new AtomicInteger());
        while (true) {
            int current = used.get();
            if (current <= 0) {
                // Defensive: indicates a double-release bug rather than user error.
                throw new IllegalStateException("negative quota release for tenant " + tenantId);
            }
            if (used.compareAndSet(current, current - 1)) {
                return;
            }
        }
    }

    public int used(String tenantId) {
        AtomicInteger used = usedByTenant.get(tenantId);
        return used == null ? 0 : used.get();
    }

    public int quota(String tenantId) {
        return quotaOverrides.getOrDefault(tenantId, defaultQuota);
    }

    /** Test/admin helper: override quota for a specific tenant. */
    public void setQuota(String tenantId, int quota) {
        quotaOverrides.put(tenantId, quota);
    }

    /** Test/admin helper: change the default quota applied to new tenants. */
    public void setDefaultQuota(int quota) {
        this.defaultQuota = quota;
    }
}
