package com.github.xingjianluo.previewenvorchestratorjava.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables the scheduled lease-reaper tick.
 * TODO: conditionally enable scheduling.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
