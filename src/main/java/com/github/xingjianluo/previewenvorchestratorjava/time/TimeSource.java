package com.github.xingjianluo.previewenvorchestratorjava.time;

/**
 * Abstraction over "current time" so that time-dependent logic (idle lease
 * reaping) can be driven deterministically in tests without real waiting.
 */
public interface TimeSource {

    long nowMillis();
}
