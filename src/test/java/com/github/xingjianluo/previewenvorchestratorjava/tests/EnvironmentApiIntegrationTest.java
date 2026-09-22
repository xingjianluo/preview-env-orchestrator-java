package com.github.xingjianluo.previewenvorchestratorjava.tests;

import com.fasterxml.jackson.databind.JsonNode;

import com.github.xingjianluo.previewenvorchestratorjava.tests.support.TestConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end HTTP behavior: idempotency headers, structured error codes,
 * request-id propagation and tenant isolation over the real Spring stack.
 */
@SpringBootTest(classes = {
        com.github.xingjianluo.previewenvorchestratorjava.Application.class,
        TestConfig.class
})
@AutoConfigureMockMvc
class EnvironmentApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("create requires Idempotency-Key and returns 400 without it")
    void requiresIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/tenants/t1/environments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"image\":\"img\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    @DisplayName("duplicate POST with same key returns the same single environment")
    void duplicateCreateIsIdempotent() throws Exception {
        MvcResult first = mockMvc.perform(post("/api/tenants/t1/environments")
                        .header("Idempotency-Key", "create-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"image\":\"img\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("RUNNING"))
                .andReturn();
        String firstId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(first.getResponse().getContentAsString()).get("envId").asText();

        MvcResult second = mockMvc.perform(post("/api/tenants/t1/environments")
                        .header("Idempotency-Key", "create-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"image\":\"img\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String secondId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(second.getResponse().getContentAsString()).get("envId").asText();
        assertEquals(firstId, secondId);
    }

    @Test
    @DisplayName("quota exhaustion surfaces as 429 QUOTA_EXCEEDED")
    void quotaExhausted() throws Exception {
        for (int i = 1; i <= 2; i++) {
            mockMvc.perform(post("/api/tenants/tq/environments")
                            .header("Idempotency-Key", "q" + i)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"image\":\"img\"}"))
                    .andExpect(status().isCreated());
        }
        mockMvc.perform(post("/api/tenants/tq/environments")
                        .header("Idempotency-Key", "q3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"image\":\"img\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("QUOTA_EXCEEDED"));
    }

    @Test
    @DisplayName("cross-tenant access is forbidden with TENANT_MISMATCH")
    void crossTenantForbidden() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/tenants/ta/environments")
                        .header("Idempotency-Key", "a1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"image\":\"img\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(created.getResponse().getContentAsString());
        String envId = body.get("envId").asText();

        mockMvc.perform(post("/api/tenants/tb/environments/" + envId + "/stop")
                        .header("Idempotency-Key", "b1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("TENANT_MISMATCH"));

        // Unknown/other-tenant env via GET behaves as not found.
        mockMvc.perform(get("/api/tenants/tb/environments/" + envId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ENV_NOT_FOUND"));
    }

    @Test
    @DisplayName("illegal transition returns 409 INVALID_STATE_TRANSITION with reason")
    void illegalTransitionViaHttp() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/tenants/tl/environments")
                        .header("Idempotency-Key", "l1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"image\":\"img\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String envId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(created.getResponse().getContentAsString()).get("envId").asText();

        // Already RUNNING -> start is illegal.
        mockMvc.perform(post("/api/tenants/tl/environments/" + envId + "/start")
                        .header("Idempotency-Key", "l2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    @DisplayName("client X-Request-Id is echoed and appears in error body")
    void requestIdEchoed() throws Exception {
        mockMvc.perform(post("/api/tenants/tx/environments")
                        .header("X-Request-Id", "fixed-request-42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"image\":\"img\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Request-Id", "fixed-request-42"))
                .andExpect(jsonPath("$.requestId").value("fixed-request-42"));
    }
}
