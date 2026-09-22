package com.github.xingjianluo.previewenvorchestratorjava.lease;

import com.github.xingjianluo.previewenvorchestratorjava.service.EnvironmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 租约回收器：定期扫描空闲超时环境并自动停止/销毁。
 * 时间来源为可注入 Clock（见 EnvironmentService），测试中可确定性推进。
 */
@Component
public class LeaseReaper {

    private static final Logger log = LoggerFactory.getLogger(LeaseReaper.class);

    private final EnvironmentService service;

    public LeaseReaper(EnvironmentService service) {
        this.service = service;
    }

    /** 执行一轮回收，返回本轮状态发生变化的环境数。 */
    @Scheduled(fixedDelayString = "${orchestrator.reaper-interval-ms:60000}")
    public int sweepOnce() {
        int reclaimed = service.reapIdle();
        if (reclaimed > 0) {
            log.info("lease sweep reclaimed={} (cause=LEASE_EXPIRED)", reclaimed);
        }
        return reclaimed;
    }
}
