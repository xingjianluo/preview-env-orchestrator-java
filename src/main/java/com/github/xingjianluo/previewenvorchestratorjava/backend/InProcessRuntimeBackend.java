package com.github.xingjianluo.previewenvorchestratorjava.backend;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 进程内模拟运行后端：用内存表模拟实例载体。
 * 所有操作幂等；提供失败注入钩子供测试确定性复现后端故障。
 */
@Component
public class InProcessRuntimeBackend implements RuntimeBackend {

    private enum BackendState { PROVISIONED, RUNNING, STOPPED }

    private final Map<String, BackendState> instances = new ConcurrentHashMap<>();
    /** 测试注入：下次调用这些操作时抛错一次。 */
    private final Set<String> failNextOps = ConcurrentHashMap.newKeySet();
    private final AtomicInteger provisionCalls = new AtomicInteger();

    private void maybeFail(String op) {
        if (failNextOps.remove(op)) {
            throw new IllegalStateException("injected backend failure on " + op);
        }
    }

    /** 测试钩子：让指定操作的下一次调用失败。 */
    public void failNext(String op) {
        failNextOps.add(op);
    }

    public int provisionCalls() {
        return provisionCalls.get();
    }

    @Override
    public String provision(String envId, String tenantId) {
        provisionCalls.incrementAndGet();
        maybeFail("provision");
        // putIfAbsent 保证重复开通幂等，不会产生第二个实例
        instances.putIfAbsent(envId, BackendState.PROVISIONED);
        return "inproc://" + envId;
    }

    @Override
    public void start(String envId) {
        maybeFail("start");
        instances.computeIfPresent(envId, (k, v) -> BackendState.RUNNING);
    }

    @Override
    public void stop(String envId) {
        maybeFail("stop");
        instances.computeIfPresent(envId, (k, v) -> BackendState.STOPPED);
    }

    @Override
    public void destroy(String envId) {
        maybeFail("destroy");
        instances.remove(envId);
    }

    @Override
    public boolean exists(String envId) {
        return instances.containsKey(envId);
    }
}
