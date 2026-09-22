package com.github.xingjianluo.previewenvorchestratorjava.tests.support;

import com.github.xingjianluo.previewenvorchestratorjava.time.TimeSource;

/**
 * Manually advanced clock. Time only moves when a test calls {@link #advance},
 * so all lease/expiry tests are deterministic and free of real waiting.
 */
public class TestClock implements TimeSource {

    private volatile long now;

    public TestClock() {
        this(1_000_000L);
    }

    public TestClock(long startMillis) {
        this.now = startMillis;
    }

    @Override
    public long nowMillis() {
        return now;
    }

    public void advance(long deltaMillis) {
        this.now += deltaMillis;
    }

    public void set(long millis) {
        this.now = millis;
    }
}
