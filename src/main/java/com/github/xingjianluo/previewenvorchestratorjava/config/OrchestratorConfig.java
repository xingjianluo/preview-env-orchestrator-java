package com.github.xingjianluo.previewenvorchestratorjava.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class OrchestratorConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
