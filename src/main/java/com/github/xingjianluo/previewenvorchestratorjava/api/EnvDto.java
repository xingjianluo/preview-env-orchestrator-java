package com.github.xingjianluo.previewenvorchestratorjava.api;

import com.github.xingjianluo.previewenvorchestratorjava.domain.EnvRecord;
import com.github.xingjianluo.previewenvorchestratorjava.service.OperationResult;

import java.time.Instant;

/**
 * 环境视图。变更类接口额外携带本次请求回执摘要：
 * requestId（用于回查）、receiptStatus、stateChanged（是否真的推动了状态变化）。
 */
public record EnvDto(String envId, String tenantId, String state, String stopCause,
                     Instant lastActiveAt, long version,
                     String requestId, String receiptStatus, boolean stateChanged) {

    public static EnvDto from(EnvRecord r) {
        return new EnvDto(r.envId(), r.tenantId(), r.state().name(), r.stopCause().name(),
                r.lastActiveAt(), r.version(), null, null, false);
    }

    public static EnvDto from(OperationResult result) {
        EnvRecord r = result.env();
        if (r == null) {
            // 请求仍在处理中（如同标识并发提交），只有回执结论、尚无环境快照
            return new EnvDto(null, null, null, null, null, 0L,
                    result.receipt().requestId(),
                    result.receipt().status().name(),
                    false);
        }
        return new EnvDto(r.envId(), r.tenantId(), r.state().name(), r.stopCause().name(),
                r.lastActiveAt(), r.version(),
                result.receipt().requestId(),
                result.receipt().status().name(),
                result.receipt().stateChanged());
    }
}
