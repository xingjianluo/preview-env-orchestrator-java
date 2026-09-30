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
 * REST 层：X-Request-Id 请求标识、响应回执 requestId、回执查询端点、
 * 无标识创建各自独立、开通未完成阶段拒绝原因可区分。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RequestReceiptApiTest {

    @Autowired
    private MockMvc mvc;

    private MvcResult createWith(String tenant, String requestId, String body) throws Exception {
        return mvc.perform(post("/api/envs")
                        .header("X-Tenant-Id", tenant)
                        .header("X-Request-Id", requestId == null ? "" : requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private static String jsonField(MvcResult r, String field) throws Exception {
        String body = r.getResponse().getContentAsString();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile(".*\"" + field + "\":\"([^\"]+)\".*").matcher(body);
        return m.matches() ? m.group(1) : null;
    }

    @Test
    void sameRequestIdMergesAndReceiptIsQueryable() throws Exception {
        String body = "{\"tenantId\":\"t1\",\"idempotencyKey\":null}";
        MvcResult first = createWith("t1", "rid-100", body);
        String envId = jsonField(first, "envId");
        org.junit.jupiter.api.Assertions.assertEquals("rid-100", jsonField(first, "requestId"),
                "调用方传入的请求标识必须原样回填");

        MvcResult second = createWith("t1", "rid-100", body);
        org.junit.jupiter.api.Assertions.assertEquals(envId, jsonField(second, "envId"),
                "同一请求标识的重试必须合并到同一环境");

        mvc.perform(get("/api/envs/requests/rid-100").header("X-Tenant-Id", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.operation").value("CREATE"))
                .andExpect(jsonPath("$.envId").value(envId))
                .andExpect(jsonPath("$.stateChanged").value(true));
    }

    @Test
    void twoKeylessCreatesAreIndependentAndServerGeneratesRequestIds() throws Exception {
        String body = "{\"tenantId\":\"t2\",\"idempotencyKey\":null}";
        MvcResult a = createWith("t2", null, body);
        MvcResult b = createWith("t2", null, body);
        String idA = jsonField(a, "envId");
        String idB = jsonField(b, "envId");
        org.junit.jupiter.api.Assertions.assertNotEquals(idA, idB, "无标识两次创建必须独立");
        org.junit.jupiter.api.Assertions.assertNotNull(jsonField(a, "requestId"));
        org.junit.jupiter.api.Assertions.assertNotEquals(jsonField(a, "requestId"),
                jsonField(b, "requestId"), "服务端生成的请求标识必须不同");

        // 服务端生成的标识也能回查到成功回执
        mvc.perform(get("/api/envs/requests/" + jsonField(a, "requestId")).header("X-Tenant-Id", "t2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.envId").value(idA));
    }

    @Test
    void receiptStatusTracksStartStopAndHistoryStaysStable() throws Exception {
        MvcResult created = createWith("t3", "rid-c", "{\"tenantId\":\"t3\"}");
        String envId = jsonField(created, "envId");

        mvc.perform(post("/api/envs/{id}/start", envId)
                        .header("X-Tenant-Id", "t3").header("X-Request-Id", "rid-s"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("RUNNING"));
        mvc.perform(post("/api/envs/{id}/stop", envId)
                        .header("X-Tenant-Id", "t3").header("X-Request-Id", "rid-x"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("STOPPED"));

        // 后续操作后，创建回执历史结论仍稳定为 CREATING->STOPPED
        mvc.perform(get("/api/envs/requests/rid-c").header("X-Tenant-Id", "t3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.fromState").value("CREATING"))
                .andExpect(jsonPath("$.toState").value("STOPPED"));

        mvc.perform(get("/api/envs/requests/rid-s").header("X-Tenant-Id", "t3"))
                .andExpect(jsonPath("$.toState").value("RUNNING"));
        mvc.perform(get("/api/envs/requests/rid-x").header("X-Tenant-Id", "t3"))
                .andExpect(jsonPath("$.toState").value("STOPPED"));
    }

    @Test
    void unknownReceiptIs404AndCrossTenantIs403() throws Exception {
        mvc.perform(get("/api/envs/requests/missing").header("X-Tenant-Id", "t4"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("REQUEST_NOT_FOUND"));

        createWith("t4", "owned-rid", "{\"tenantId\":\"t4\"}");
        mvc.perform(get("/api/envs/requests/owned-rid").header("X-Tenant-Id", "t5"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("TENANT_FORBIDDEN"));
    }
}
