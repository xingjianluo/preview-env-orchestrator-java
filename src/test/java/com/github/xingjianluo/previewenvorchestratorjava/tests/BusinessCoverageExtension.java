package com.github.xingjianluo.previewenvorchestratorjava.tests;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 业务覆盖日志扩展（自动注册，见 src/test/resources/junit-platform.properties）。
 *
 * <p>每个用例结束打一行结果，每个测试类结束打一行汇总，
 * 使 {@code mvn test} 的日志能直接看出覆盖了哪些业务、各业务用例通过情况：
 * <pre>
 * [业务覆盖] CreateRetryConsistencyTest :: keylessRetryWithSameRequestIdMergesIntoOneEnvAndOneQuota -> 通过
 * [业务汇总] CreateRetryConsistencyTest 用例=5 通过=5 失败=0 跳过=0
 * </pre>
 */
public class BusinessCoverageExtension implements TestWatcher, AfterAllCallback {

    private static final Logger log = LoggerFactory.getLogger(BusinessCoverageExtension.class);

    private static final Map<String, Counters> COUNTERS = new ConcurrentHashMap<>();

    private static final class Counters {
        final AtomicInteger total = new AtomicInteger();
        final AtomicInteger passed = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        final AtomicInteger skipped = new AtomicInteger();
    }

    private static Counters counters(ExtensionContext context) {
        return COUNTERS.computeIfAbsent(context.getRequiredTestClass().getSimpleName(),
                k -> new Counters());
    }

    @Override
    public void testSuccessful(ExtensionContext context) {
        counters(context).total.incrementAndGet();
        counters(context).passed.incrementAndGet();
        log.info("[业务覆盖] {} :: {} -> 通过", context.getRequiredTestClass().getSimpleName(),
                context.getRequiredTestMethod().getName());
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        counters(context).total.incrementAndGet();
        counters(context).failed.incrementAndGet();
        log.warn("[业务覆盖] {} :: {} -> 失败: {}", context.getRequiredTestClass().getSimpleName(),
                context.getRequiredTestMethod().getName(), cause.getMessage());
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        counters(context).total.incrementAndGet();
        counters(context).skipped.incrementAndGet();
        log.info("[业务覆盖] {} :: {} -> 中止", context.getRequiredTestClass().getSimpleName(),
                context.getRequiredTestMethod().getName());
    }

    @Override
    public void testDisabled(ExtensionContext context, java.util.Optional<String> reason) {
        counters(context).total.incrementAndGet();
        counters(context).skipped.incrementAndGet();
        log.info("[业务覆盖] {} :: {} -> 跳过", context.getRequiredTestClass().getSimpleName(),
                context.getRequiredTestMethod().getName());
    }

    @Override
    public void afterAll(ExtensionContext context) {
        String name = context.getRequiredTestClass().getSimpleName();
        Counters c = counters(context);
        log.info("[业务汇总] {} 用例={} 通过={} 失败={} 跳过={}",
                name, c.total.get(), c.passed.get(), c.failed.get(), c.skipped.get());
    }
}
