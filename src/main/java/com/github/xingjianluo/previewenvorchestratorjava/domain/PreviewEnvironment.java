package com.github.xingjianluo.previewenvorchestratorjava.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A preview environment instance and its lifecycle bookkeeping.
 *
 * <p>Mutable: all reads and writes must be performed while holding the
 * per-environment lock owned by the orchestrator, so concurrent requests for
 * the same environment are serialized and can never overwrite each other's
 * state.
 */
public final class PreviewEnvironment {

    private final String envId;
    private final String tenantId;
    private final String image;
    private final long createdAt;

    private EnvState state = EnvState.CREATING;
    private long lastActivityAt;
    private long stoppedAt;
    private final List<StateTransition> transitions = new ArrayList<>();

    public PreviewEnvironment(String envId, String tenantId, String image, long nowMillis) {
        this.envId = envId;
        this.tenantId = tenantId;
        this.image = image;
        this.createdAt = nowMillis;
        this.lastActivityAt = nowMillis;
    }

    public String getEnvId() {
        return envId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getImage() {
        return image;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public EnvState getState() {
        return state;
    }

    public long getLastActivityAt() {
        return lastActivityAt;
    }

    public long getStoppedAt() {
        return stoppedAt;
    }

    public List<StateTransition> getTransitions() {
        return Collections.unmodifiableList(transitions);
    }

    /** Reason of the most recent transition, or null if none recorded yet. */
    public TransitionReason lastReason() {
        return transitions.isEmpty() ? null : transitions.get(transitions.size() - 1).reason();
    }

    /** Record the synthetic null -> CREATING entry at construction time. */
    public void markBorn(long atMillis) {
        transitions.add(new StateTransition(null, EnvState.CREATING,
                TransitionReason.CREATE_REQUESTED, atMillis));
    }

    /**
     * Apply a lifecycle transition, recording it in the audit history.
     *
     * @param touchActivity when true, the environment is considered active at
     *                      {@code atMillis} (manual start/stop/create); the
     *                      lease reaper's automatic transitions do not refresh
     *                      activity so reaping stays monotonic.
     */
    public void transitionTo(EnvState target, TransitionReason reason, long atMillis,
                             boolean touchActivity) {
        EnvState from = this.state;
        this.state = target;
        this.transitions.add(new StateTransition(from, target, reason, atMillis));
        if (touchActivity) {
            this.lastActivityAt = atMillis;
        }
        if (target == EnvState.STOPPED) {
            this.stoppedAt = atMillis;
        }
    }

    /** Explicitly refresh the idle-activity timestamp (e.g. successful start). */
    public void touch(long atMillis) {
        this.lastActivityAt = atMillis;
    }
}
