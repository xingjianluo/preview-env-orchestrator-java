package com.github.xingjianluo.previewenvorchestratorjava.tests.orchestrator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST 层集成测试：验证 HTTP 状态码与失败原因的可区分映射。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EnvApiTest {

    @Autowired
    private MockMvc mvc;

    private String createEnv(String tenant, String key) throws Exception {
        MvcResult result = mvc.perform(post("/api/envs")
                        .header("X-Tenant-Id", tenant)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"" + tenant + "\",\"idempotencyKey\":\"" + key + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("STOPPED"))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        return body.replaceAll(".*\"envId\":\"([^\"]+)\".*", "$1");
    }

    @Test
    void restLifecycleAndErrorMapping() throws Exception {
        String envId = createEnv("api-tenant", "api-key-1");

        // 幂等重复创建 -> 同一 envId
        assertEquals(envId, createEnv("api-tenant", "api-key-1"));

        // 重复停止 -> 409 ILLEGAL_STATE_TRANSITION
        mvc.perform(post("/api/envs/{id}/stop", envId).header("X-Tenant-Id", "api-tenant"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("ILLEGAL_STATE_TRANSITION"));

        // 跨租户 -> 403 TENANT_FORBIDDEN
        mvc.perform(get("/api/envs/{id}", envId).header("X-Tenant-Id", "other-tenant"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("TENANT_FORBIDDEN"));

        // 不存在 -> 404 ENV_NOT_FOUND
        mvc.perform(get("/api/envs/{id}", "missing").header("X-Tenant-Id", "api-tenant"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("ENV_NOT_FOUND"));

        // 正常启动 -> 停止 -> 销毁
        mvc.perform(post("/api/envs/{id}/start", envId).header("X-Tenant-Id", "api-tenant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RUNNING"));
        mvc.perform(post("/api/envs/{id}/stop", envId).header("X-Tenant-Id", "api-tenant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stopCause").value("MANUAL"));
        mvc.perform(delete("/api/envs/{id}", envId).header("X-Tenant-Id", "api-tenant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("DESTROYED"));
    }
}
