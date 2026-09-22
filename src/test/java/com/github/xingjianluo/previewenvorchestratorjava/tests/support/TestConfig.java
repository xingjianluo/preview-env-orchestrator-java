package com.github.xingjianluo.previewenvorchestratorjava.tests.support;

import com.github.xingjianluo.previewenvorchestratorjava.backend.InProcessRuntimeBackend;
import com.github.xingjianluo.previewenvorchestratorjava.time.TimeSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Wires a controllable {@link TestClock} and a real
 * {@link InProcessRuntimeBackend} (with failure injection) into tests.
 */
@TestConfiguration
public class TestConfig {

    @Bean
    @Primary
    public TestClock testClock() {
        return new TestClock();
    }

    @Bean
    @Primary
    public InProcessRuntimeBackend testBackend() {
        return new InProcessRuntimeBackend();
    }
}
