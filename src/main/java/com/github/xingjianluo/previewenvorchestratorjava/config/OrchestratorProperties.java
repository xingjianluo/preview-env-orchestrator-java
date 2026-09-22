package com.github.xingjianluo.previewenvorchestratorjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 编排参数：租户配额与租约时长。
 */
@Component
@ConfigurationProperties(prefix = "orchestrator")
public class OrchestratorProperties {

    /** 单租户允许同时存活（非 DESTROYED）的环境数上限。 */
    private int maxEnvsPerTenant = 5;

    /** RUNNING 环境空闲超过该时长被自动停止。 */
    private Duration idleStopTtl = Duration.ofMinutes(30);

    /** STOPPED 环境空闲超过该时长被自动销毁回收。 */
    private Duration idleDestroyTtl = Duration.ofHours(2);

    public OrchestratorProperties() {
    }

    public OrchestratorProperties(int maxEnvsPerTenant, Duration idleStopTtl, Duration idleDestroyTtl) {
        this.maxEnvsPerTenant = maxEnvsPerTenant;
        this.idleStopTtl = idleStopTtl;
        this.idleDestroyTtl = idleDestroyTtl;
    }

    public int getMaxEnvsPerTenant() { return maxEnvsPerTenant; }
    public void setMaxEnvsPerTenant(int v) { this.maxEnvsPerTenant = v; }
    public Duration getIdleStopTtl() { return idleStopTtl; }
    public void setIdleStopTtl(Duration v) { this.idleStopTtl = v; }
    public Duration getIdleDestroyTtl() { return idleDestroyTtl; }
    public void setIdleDestroyTtl(Duration v) { this.idleDestroyTtl = v; }
}
