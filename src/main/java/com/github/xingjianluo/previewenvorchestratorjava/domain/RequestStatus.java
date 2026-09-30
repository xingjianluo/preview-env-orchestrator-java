package com.github.xingjianluo.previewenvorchestratorjava.domain;

/**
 * 请求回执状态：处理中 / 成功（终态，结论稳定不可变）/ 失败（可用同一请求标识安全重试）。
 */
public enum RequestStatus {
    PROCESSING,
    SUCCEEDED,
    FAILED
}
