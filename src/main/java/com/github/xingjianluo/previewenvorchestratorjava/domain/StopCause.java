package com.github.xingjianluo.previewenvorchestratorjava.domain;

/**
 * 停止/销毁的触发来源，用于区分手动操作与租约回收。
 */
public enum StopCause {
    NONE,
    MANUAL,
    LEASE_EXPIRED,
    /** 开通超时/失败后长期滞留 CREATING，被回收器清理。 */
    PROVISION_TIMEOUT
}
