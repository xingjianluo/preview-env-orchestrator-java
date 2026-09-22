package com.github.xingjianluo.previewenvorchestratorjava.idem;

import com.github.xingjianluo.previewenvorchestratorjava.error.ErrorCode;
import com.github.xingjianluo.previewenvorchestratorjava.error.OrchestratorException;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Stores outcomes of mutating requests keyed by {@code (scope, idempotency key)}.
 *
 * <p>Guarantees:
 * <ul>
 *   <li><b>Exactly-once execution.</b> Concurrent duplicate submissions are
 *       coalesced on a per-key monitor: only one request runs the action, the
 *       rest wait and observe the very same outcome — no second environment
 *       instance can be created.</li>
 *   <li><b>Transient failures are not cached.</b> If the action throws
 *       (backend fault, quota exhaustion), no outcome is recorded; a waiting
 *       duplicate or a later retry gets to execute again.</li>
 *   <li><b>Terminal outcomes are cached.</b> Success and definitive rejections
 *       are replayed verbatim on replay.</li>
 *   <li><b>Scope conflicts are detected.</b> The same key used for a different
 *       operation yields {@link ErrorCode#IDEMPOTENCY_CONFLICT}.</li>
 * </ul>
 */
@Component
public class IdempotencyStore {

    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();

    private static String compoundKey(String scope, String idempotencyKey) {
        return scope + "|" + idempotencyKey;
    }

    private static final class Entry {
        final String scope;
        volatile IdempotencyOutcome outcome;
        boolean executing;

        Entry(String scope) {
            this.scope = scope;
        }
    }

    public IdempotencyOutcome execute(String scope, String idempotencyKey,
                                      Supplier<IdempotencyOutcome> action) {
        Entry entry = entries.computeIfAbsent(compoundKey(scope, idempotencyKey),
                k -> new Entry(scope));
        synchronized (entry) {
            if (!entry.scope.equals(scope)) {
                throw new OrchestratorException(ErrorCode.IDEMPOTENCY_CONFLICT,
                        "idempotency key '" + idempotencyKey
                                + "' is already associated with a different operation");
            }
            while (true) {
                if (entry.outcome != null) {
                    return entry.outcome;
                }
                if (!entry.executing) {
                    entry.executing = true;
                    break;
                }
                try {
                    entry.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new OrchestratorException(ErrorCode.CONFLICT,
                            "interrupted while waiting for duplicate request", e);
                }
            }
        }

        IdempotencyOutcome result;
        try {
            result = action.get();
        } catch (RuntimeException e) {
            // Transient: do not persist; release the slot so a retry can run.
            synchronized (entry) {
                entry.executing = false;
                entry.notifyAll();
            }
            throw e;
        }

        synchronized (entry) {
            entry.outcome = result;
            entry.executing = false;
            entry.notifyAll();
        }
        return result;
    }

    public Optional<IdempotencyOutcome> find(String scope, String idempotencyKey) {
        Entry entry = entries.get(compoundKey(scope, idempotencyKey));
        return entry == null ? Optional.empty() : Optional.ofNullable(entry.outcome);
    }
}
