package com.github.xingjianluo.previewenvorchestratorjava.lease;

import com.github.xingjianluo.previewenvorchestratorjava.error.ErrorCode;
import com.github.xingjianluo.previewenvorchestratorjava.error.OrchestratorException;
import com.github.xingjianluo.previewenvorchestratorjava.service.EnvironmentOrchestrator;
import com.github.xingjianluo.previewenvorchestratorjava.time.TimeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically drives {@link EnvironmentOrchestrator#reclaimIdleEnvironments(long)}.
 *
 * <p>Enabled only when {@code orchestrator.lease.enabled=true}. Tests leave it
 * disabled and advance a controllable clock manually, then invoke a single
 * tick — time-based behavior is therefore deterministic and free of real
 * waiting. A transient backend failure during reaping is logged and retried on
 * the next tick; partial destruction leaves the env in FAILED with its
 * resource still allocated, so nothing is silently lost.
 */
@Component
@ConditionalOnProperty(name = "orchestrator.lease.enabled", havingValue = "true")
public class LeaseReaper {

    private static final Logger log = LoggerFactory.getLogger(LeaseReaper.class);

    private final EnvironmentOrchestrator orchestrator;
    private final TimeSource timeSource;

    public LeaseReaper(EnvironmentOrchestrator orchestrator, TimeSource timeSource) {
        this.orchestrator = orchestrator;
        this.timeSource = timeSource;
    }

    @Scheduled(fixedDelayString = "${orchestrator.lease.interval-millis:5000}")
    public void scheduledTick() {
        tick();
    }

    /**
     * Run one reclamation pass. Only one environment is reclaimed per tick
     * (stop takes precedence over destroy); repeated invocations drain the
     * rest. Backend faults are swallowed here because they are independently
     * retry-safe.
     */
    public String tick() {
        String previous = MDC.get("requestId");
        MDC.put("requestId", "reaper-" + timeSource.nowMillis());
        try {
            String action = orchestrator.reclaimIdleEnvironments(timeSource.nowMillis());
            if (action != null) {
                log.info("requestId={} op=REAPER result={} basis=[lease due]",
                        MDC.get("requestId"), action);
            }
            return action;
        } catch (OrchestratorException e) {
            if (e.getErrorCode() == ErrorCode.BACKEND_FAILURE) {
                log.warn("requestId={} op=REAPER result=RETRY basis=[{}]",
                        MDC.get("requestId"), e.getMessage());
                return "RETRY";
            }
            throw e;
        } finally {
            if (previous != null) {
                MDC.put("requestId", previous);
            } else {
                MDC.remove("requestId");
            }
        }
    }
}
