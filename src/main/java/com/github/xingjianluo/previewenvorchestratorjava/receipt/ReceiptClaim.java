package com.github.xingjianluo.previewenvorchestratorjava.receipt;

import com.github.xingjianluo.previewenvorchestratorjava.domain.RequestReceipt;

/**
 * {@link ReceiptStore#claim} 的判定结果：调用方据此决定是首次执行还是复用历史结论。
 */
public record ReceiptClaim(RequestReceipt receipt, boolean acquired) {

    /** 首次占位成功，调用方应执行操作。 */
    public static ReceiptClaim acquired(RequestReceipt r) {
        return new ReceiptClaim(r, true);
    }

    /** 命中已有回执（处理中/成功/失败），调用方应复用而非重新执行。 */
    public static ReceiptClaim existing(RequestReceipt r) {
        return new ReceiptClaim(r, false);
    }
}
