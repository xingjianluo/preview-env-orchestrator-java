package com.github.xingjianluo.previewenvorchestratorjava.service;

import com.github.xingjianluo.previewenvorchestratorjava.domain.EnvRecord;
import com.github.xingjianluo.previewenvorchestratorjava.receipt.RequestReceipt;

/**
 * 一次变更操作的返回：环境最新记录 + 本次请求回执。
 */
public record OperationResult(EnvRecord env, RequestReceipt receipt) {
}
