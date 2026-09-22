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
                         @RequestBody CreateEnvRequest req) {
        return EnvDto.from(service.create(tenantId, req.idempotencyKey()));
    }

    @PostMapping("/{envId}/start")
    public EnvDto start(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String envId) {
        return EnvDto.from(service.start(tenantId, envId));
    }

    @PostMapping("/{envId}/stop")
    public EnvDto stop(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String envId) {
        return EnvDto.from(service.stop(tenantId, envId));
    }

    @PostMapping("/{envId}/keep-alive")
    public EnvDto keepAlive(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String envId) {
        return EnvDto.from(service.keepAlive(tenantId, envId));
    }

    @DeleteMapping("/{envId}")
    public EnvDto destroy(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String envId) {
        return EnvDto.from(service.destroy(tenantId, envId));
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
