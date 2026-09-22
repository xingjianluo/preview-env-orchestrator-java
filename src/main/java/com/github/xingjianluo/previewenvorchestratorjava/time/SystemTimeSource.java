package com.github.xingjianluo.previewenvorchestratorjava.time;

import org.springframework.stereotype.Component;

/** Wall-clock time source used outside tests. */
@Component
public class SystemTimeSource implements TimeSource {

    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }
}
