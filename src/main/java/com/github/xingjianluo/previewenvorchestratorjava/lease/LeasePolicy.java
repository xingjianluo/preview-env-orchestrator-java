package com.github.xingjianluo.previewenvorchestratorjava.lease;

import com.github.xingjianluo.previewenvorchestratorjava.domain.EnvState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Declarative idle-lease rules:
 * <ul>
 *   <li>a RUNNING environment whose last activity is older than
 *       {@code idleStopMillis} is <b>automatically stopped</b>
 *       (reason {@code IDLE_STOP_LEASE});</li>
 *   <li>a STOPPED environment that has remained stopped past
 *       {@code idleDestroyMillis} is <b>automatically destroyed</b>, returning
 *       its quota slot (reason {@code IDLE_DESTROY_LEASE}).</li>
 * </ul>
 *
 * <p>The policy contains no clock of its own — all decisions are pure
 * functions of the injected {@code now}, which makes reaper tests
 * deterministic.
 */
@Component
public class LeasePolicy {

    @Value("${orchestrator.lease.idle-stop-millis:30000}")
    private long idleStopMillis;

    @Value("${orchestrator.lease.idle-destroy-millis:60000}")
    private long idleDestroyMillis;

    public boolean shouldIdleStop(EnvState state, long lastActivityAt, long now) {
        return state == EnvState.RUNNING
                && idleStopMillis > 0
                && now - lastActivityAt >= idleStopMillis;
    }

    public boolean shouldIdleDestroy(EnvState state, long stoppedAt, long now) {
        return state == EnvState.STOPPED
                && idleDestroyMillis > 0
                && stoppedAt > 0
                && now - stoppedAt >= idleDestroyMillis;
    }

    public long idleStopMillis() {
        return idleStopMillis;
    }

    public long idleDestroyMillis() {
        return idleDestroyMillis;
    }
}
