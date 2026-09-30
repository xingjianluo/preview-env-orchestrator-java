package com.github.xingjianluo.previewenvorchestratorjava.tests.orchestrator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST 层：请求标识请求头、回执回查端点、阶段拒绝原因的 HTTP 映射。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReceiptApiTest {

    @Autowired
    private MockMvc mvc;

    private String extract(String body, String field) {
        return body.replaceAll(".*\"" + field + "\":\"([^\"]+)\".*", "$1");
    }

    @Test
    void requestIdHeaderAndReceiptEndpointWorkEndToEnd() throws Exception {
        // 带 X-Request-Id 创建，响应回显该标识与成功回执
        MvcResult created = mvc.perform(post("/api/envs")
                        .header("X-Tenant-Id", "rcpt-t")
                        .header("X-Request-Id", "client-create-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"k-1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestId").value("client-create-1"))
                .andExpect(jsonPath("$.receiptStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.stateChanged").value(true))
                .andReturn();
        String envId = extract(created.getResponse().getContentAsString(), "envId");

        // 回查创建回执：结论稳定
        mvc.perform(get("/api/envs/requests/client-create-1").header("X-Tenant-Id", "rcpt-t"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("client-create-1"))
                .andExpect(jsonPath("$.operation").value("CREATE"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.envId").value(envId))
                .andExpect(jsonPath("$.stateChanged").value(true));

        // 同标识重放：结论冻结，不重复开通（仍 200）
        mvc.perform(post("/api/envs")
                        .header("X-Tenant-Id", "rcpt-t")
                        .header("X-Request-Id", "client-create-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"k-1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.envId").value(envId));

        // 启动并回查启动回执
        mvc.perform(post("/api/envs/{id}/start", envId)
                        .header("X-Tenant-Id", "rcpt-t")
                        .header("X-Request-Id", "client-start-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receiptStatus").value("SUCCEEDED"));
        mvc.perform(get("/api/envs/requests/client-start-1").header("X-Tenant-Id", "rcpt-t"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("START"))
                .andExpect(jsonPath("$.snapshotState").value("RUNNING"));

        // 同一请求标识跨操作复用 -> 409 REQUEST_KIND_CONFLICT
        mvc.perform(post("/api/envs/{id}/stop", envId)
                        .header("X-Tenant-Id", "rcpt-t")
                        .header("X-Request-Id", "client-start-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("REQUEST_KIND_CONFLICT"));

        // 回查不存在的请求标识 -> 404 RECEIPT_NOT_FOUND
        mvc.perform(get("/api/envs/requests/nope").header("X-Tenant-Id", "rcpt-t"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("RECEIPT_NOT_FOUND"));

        // 跨租户回查 -> 404（回执按租户隔离）
        mvc.perform(get("/api/envs/requests/client-create-1").header("X-Tenant-Id", "other"))
                .andExpect(status().isNotFound());
    }

    @Test
    void keylessCreatesAreIndependentOverHttpAndOldFlowStillWorks() throws Exception {
        // 不带 idempotencyKey、不带 X-Request-Id 的老用法依旧可用
        MvcResult a = mvc.perform(post("/api/envs")
                        .header("X-Tenant-Id", "anon-t")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("STOPPED"))
                .andReturn();
        MvcResult b = mvc.perform(post("/api/envs")
                        .header("X-Tenant-Id", "anon-t")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();
        String idA = extract(a.getResponse().getContentAsString(), "envId");
        String idB = extract(b.getResponse().getContentAsString(), "envId");
        org.junit.jupiter.api.Assertions.assertNotEquals(idA, idB, "两次无标识创建必须独立");

        // 服务端生成的 requestId 可用于回查
        String serverReq = extract(a.getResponse().getContentAsString(), "requestId");
        mvc.perform(get("/api/envs/requests/{rid}", serverReq).header("X-Tenant-Id", "anon-t"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.envId").value(idA));
    }
}
