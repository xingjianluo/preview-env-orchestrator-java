package com.github.xingjianluo.previewenvorchestratorjava.receipt;

import com.github.xingjianluo.previewenvorchestratorjava.domain.EnvState;
import com.github.xingjianluo.previewenvorchestratorjava.domain.FailureReason;
import com.github.xingjianluo.previewenvorchestratorjava.domain.OperationType;
import com.github.xingjianluo.previewenvorchestratorjava.domain.RequestReceipt;
import com.github.xingjianluo.previewenvorchestratorjava.domain.RequestStatus;
import com.github.xingjianluo.previewenvorchestratorjava.service.OrchestratorException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内请求回执存储：按请求标识（requestId）保存每次写操作的最终结论。
 *
 * 关键保证：
 * - claim 以 ConcurrentHashMap.compute 原子完成，同一请求标识的并发提交只有一个占位成功；
 * - 成功（SUCCEEDED）为终态，永远不会被后续操作改写，反复回查结论稳定；
 * - 失败（FAILED）允许用同一请求标识重试：重试时原子回到 PROCESSING 并由调用方重新执行；
 * - 同一标识复用到不同操作类型返回 REQUEST_CONFLICT；跨租户访问返回 TENANT_FORBIDDEN。
 */
@Component
public class ReceiptStore {

    private final Map<String, RequestReceipt> byRequestId = new ConcurrentHashMap<>();
    private final Clock clock;

    public ReceiptStore(Clock clock) {
        this.clock = clock;
    }

    private Instant now() {
        return Instant.now(clock);
    }

    /**
     * 受理一次写操作。
     *
     * @return acquired=true 表示首次占位（或上次失败后的重试），调用方应执行操作；
     *         acquired=false 表示命中已有结论（处理中/成功），调用方直接复用，不得重复执行。
     */
    public ReceiptClaim claim(String requestId, String tenantId, OperationType operation) {
        final ReceiptClaim[] result = new ReceiptClaim[1];
        byRequestId.compute(requestId, (id, existing) -> {
            if (existing == null) {
                RequestReceipt r = RequestReceipt.processing(requestId, tenantId, operation, null, now());
                result[0] = ReceiptClaim.acquired(r);
                return r;
            }
            if (!existing.tenantId().equals(tenantId)) {
                throw new OrchestratorException(FailureReason.TENANT_FORBIDDEN,
                        "request " + requestId + " belongs to another tenant");
            }
            if (existing.operation() != operation) {
                throw new OrchestratorException(FailureReason.REQUEST_CONFLICT,
                        "request " + requestId + " is bound to operation " + existing.operation()
                                + ", cannot be reused for " + operation);
            }
            if (existing.status() == RequestStatus.FAILED) {
                // 失败可安全重试：原子回到处理中，重新执行
                RequestReceipt retrying = RequestReceipt.processing(requestId, tenantId, operation,
                        existing.envId(), now());
                result[0] = ReceiptClaim.acquired(retrying);
                return retrying;
            }
            // PROCESSING（并发重复）或 SUCCEEDED（历史结论）：直接复用，绝不重复执行
            result[0] = ReceiptClaim.existing(existing);
            return existing;
        });
        return result[0];
    }

    /** 创建登记成功后把作用环境补绑到仍处处理中的回执。 */
    public void bindEnv(String requestId, String envId) {
        byRequestId.computeIfPresent(requestId, (id, r) ->
                r.envId() == null ? r.bindEnv(envId, now()) : r);
    }

    /** 标记成功（终态）；仅处理中的回执可推进，成功后不可变。 */
    public void succeeded(String requestId, boolean stateChanged, EnvState from, EnvState to) {
        byRequestId.computeIfPresent(requestId, (id, r) ->
                r.status() == RequestStatus.PROCESSING ? r.succeeded(stateChanged, from, to, now()) : r);
    }

    /** 标记失败及可区分原因；仅处理中的回执可推进，供同标识安全重试。 */
    public void failed(String requestId, FailureReason reason, String message) {
        byRequestId.computeIfPresent(requestId, (id, r) ->
                r.status() == RequestStatus.PROCESSING ? r.failed(reason, message, now()) : r);
    }

    /** 按请求标识回查回执；跨租户拒绝，不存在返回 REQUEST_NOT_FOUND。 */
    public RequestReceipt get(String requestId, String tenantId) {
        RequestReceipt r = byRequestId.get(requestId);
        if (r == null) {
            throw new OrchestratorException(FailureReason.REQUEST_NOT_FOUND,
                    "request not found: " + requestId);
        }
        if (!r.tenantId().equals(tenantId)) {
            throw new OrchestratorException(FailureReason.TENANT_FORBIDDEN,
                    "request " + requestId + " belongs to another tenant");
        }
        return r;
    }
}
