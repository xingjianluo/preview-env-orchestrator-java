package com.github.xingjianluo.previewenvorchestratorjava.domain;

/**
 * 请求回执状态：调用方据此判断这次请求的最终结论。
 */
public enum ReceiptStatus {
    /** 请求仍在处理中（尚未产生结论），稍后用同一请求标识回查。 */
    PROCESSING,
    /** 请求已成功完成，结论稳定，后续操作不会改写。 */
    SUCCEEDED,
    /** 请求已失败；retryable=true 时可用同一请求标识安全重试。 */
    FAILED
}
