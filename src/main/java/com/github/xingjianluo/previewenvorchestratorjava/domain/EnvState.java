package com.github.xingjianluo.previewenvorchestratorjava.domain;

/**
 * 预览环境生命周期状态。
 */
public enum EnvState {
    /** 已登记，等待后端完成开通（可重试）。 */
    CREATING,
    /** 后端实例已就绪但处于停止态，不计租约空闲。 */
    STOPPED,
    /** 运行中。 */
    RUNNING,
    /** 销毁中（可重试）。 */
    DESTROYING,
    /** 已销毁，终态。 */
    DESTROYED
}
