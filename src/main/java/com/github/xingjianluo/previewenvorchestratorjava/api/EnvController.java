package com.github.xingjianluo.previewenvorchestratorjava.api;

import com.github.xingjianluo.previewenvorchestratorjava.domain.EnvRecord;
import com.github.xingjianluo.previewenvorchestratorjava.domain.RequestReceipt;
import com.github.xingjianluo.previewenvorchestratorjava.service.EnvironmentService;
import com.github.xingjianluo.previewenvorchestratorjava.service.EnvironmentService.Outcome;
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
 * 写操作（创建/启动/停止/销毁）均可携带 X-Request-Id 作为请求标识：
 * 同一标识的重试合并到同一次请求，回执结论稳定；不传则服务端当次生成并在响应体 requestId 返回。
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
        Outcome r = service.createWithRequestId(tenantId, req.idempotencyKey(), requestId);
        return EnvDto.from(r.record(), r.requestId());
    }

    @PostMapping("/{envId}/start")
    public EnvDto start(@RequestHeader("X-Tenant-Id") String tenantId,
                        @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                        @PathVariable String envId) {
        Outcome r = service.startWithRequestId(tenantId, envId, requestId);
        return EnvDto.from(r.record(), r.requestId());
    }

    @PostMapping("/{envId}/stop")
    public EnvDto stop(@RequestHeader("X-Tenant-Id") String tenantId,
                       @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                       @PathVariable String envId) {
        Outcome r = service.stopWithRequestId(tenantId, envId, requestId);
        return EnvDto.from(r.record(), r.requestId());
    }

    @PostMapping("/{envId}/keep-alive")
    public EnvDto keepAlive(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String envId) {
        return EnvDto.from(service.keepAlive(tenantId, envId));
    }

    @DeleteMapping("/{envId}")
    public EnvDto destroy(@RequestHeader("X-Tenant-Id") String tenantId,
                          @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                          @PathVariable String envId) {
        Outcome r = service.destroyWithRequestId(tenantId, envId, requestId);
        return EnvDto.from(r.record(), r.requestId());
    }

    /** 按请求标识回查本次写操作的最终结论（成功/处理中/失败可重试、作用环境、是否改变状态）。 */
    @GetMapping("/requests/{requestId}")
    public ReceiptDto getReceipt(@RequestHeader("X-Tenant-Id") String tenantId,
                                 @PathVariable String requestId) {
        RequestReceipt r = service.getReceipt(tenantId, requestId);
        return ReceiptDto.from(r);
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
