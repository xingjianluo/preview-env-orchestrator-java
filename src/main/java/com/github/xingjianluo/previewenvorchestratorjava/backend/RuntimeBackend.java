package com.github.xingjianluo.previewenvorchestratorjava.backend;

/**
 * 可替换的运行后端：模拟环境实例的真实载体。
 * 所有操作必须幂等，可安全重试。
 */
public interface RuntimeBackend {

    /** 开通实例，返回后端实例句柄。 */
    String provision(String envId, String tenantId);

    /** 启动实例。 */
    void start(String envId);

    /** 停止实例。 */
    void stop(String envId);

    /** 销毁实例并释放资源。 */
    void destroy(String envId);

    /** 后端当前是否持有该实例（用于残留清理校验）。 */
    boolean exists(String envId);
}
