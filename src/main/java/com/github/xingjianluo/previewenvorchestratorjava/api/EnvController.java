package com.github.xingjianluo.previewenvorchestratorjava.api;

import com.github.xingjianluo.previewenvorchestratorjava.service.EnvironmentService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 预览环境编排 REST 入口。租户由 X-Tenant-Id 头标识。
 *
 * <p>变更类接口（创建/启动/停止/销毁）支持可选请求头 {@code X-Request-Id}：
 * 调用方用自己的请求标识提交，可用同一标识回查回执；不传时服务端生成并随响应返回。
 * 创建的去重/合并看两个标识：body.idempotencyKey 非空时按 (租户, key) 合并；
 * 不带 idempotencyKey 时按 (租户, X-Request-Id) 合并——同一请求标识的重试复用同一环境；
 * 两个标识都不带的两次创建彼此独立。
 */
@RestController
@RequestMapping("/api/envs")
public class EnvController {

    private final EnvironmentService service;

    public EnvController(EnvironmentService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EnvDto create(@RequestHeader("X-Tenant-Id") String tenantId,
                         @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                         @RequestBody CreateEnvRequest req) {
        return EnvDto.from(service.create(tenantId, req.idempotencyKey(), requestId));
    }

    @PostMapping("/{envId}/start")
    public EnvDto start(@RequestHeader("X-Tenant-Id") String tenantId,
                        @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                        @PathVariable String envId) {
        return EnvDto.from(service.start(tenantId, envId, requestId));
    }

    @PostMapping("/{envId}/stop")
    public EnvDto stop(@RequestHeader("X-Tenant-Id") String tenantId,
                       @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                       @PathVariable String envId) {
        return EnvDto.from(service.stop(tenantId, envId, requestId));
    }

    @PostMapping("/{envId}/keep-alive")
    public EnvDto keepAlive(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String envId) {
        return EnvDto.from(service.keepAlive(tenantId, envId));
    }

    @DeleteMapping("/{envId}")
    public EnvDto destroy(@RequestHeader("X-Tenant-Id") String tenantId,
                          @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                          @PathVariable String envId) {
        return EnvDto.from(service.destroy(tenantId, envId, requestId));
    }

    /** 请求回执回查：同一请求标识反复查询结论稳定。 */
    @GetMapping("/requests/{requestId}")
    public ReceiptDto getReceipt(@RequestHeader("X-Tenant-Id") String tenantId,
                                 @PathVariable String requestId) {
        return ReceiptDto.from(service.getReceipt(tenantId, requestId));
    }

    @GetMapping("/{envId}")
    public EnvDto get(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String envId) {
        return EnvDto.from(service.get(tenantId, envId));
    }

    @GetMapping
    public List<EnvDto> list(@RequestHeader("X-Tenant-Id") String tenantId) {
        return service.list(tenantId).stream().map(EnvDto::from).toList();
    }
}
