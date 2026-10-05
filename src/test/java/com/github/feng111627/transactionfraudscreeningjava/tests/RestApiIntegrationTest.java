package com.github.feng111627.transactionfraudscreeningjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 覆盖业务：REST 入口端到端行为（单笔/批量/复核/权限头/错误码）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RestApiIntegrationTest extends BusinessTest {

    @Autowired
    private MockMvc mockMvc;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void missing_operator_header_is_rejected_400() throws Exception {
        log.info("覆盖业务[REST 入口] 用例: 缺少 X-Operator 头 -> 400 且错误码明确");
        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r1\",\"accountId\":\"a\",\"targetId\":\"t\","
                                + "\"amount\":10,\"currency\":\"CNY\",\"txnTimeEpochMilli\":"
                                + System.currentTimeMillis() + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OPERATOR_MISSING"));
    }

    @Test
    void single_submit_then_idempotent_get_and_invalid_field_400() throws Exception {
        log.info("覆盖业务[REST 入口] 用例: 合法提交 200 带结论；非法金额 400 带稳定原因码");
        long time = System.currentTimeMillis();
        String body = "{\"requestId\":\"rest-1\",\"accountId\":\"A1\",\"targetId\":\"T1\","
                + "\"amount\":10,\"currency\":\"CNY\",\"txnTimeEpochMilli\":" + time + "}";
        mockMvc.perform(post("/api/transactions")
                        .header("X-Operator", "teller").header("X-Roles", "ANALYST")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.evaluation.disposition").exists())
                .andExpect(jsonPath("$.evaluation.hits").isArray());

        // 幂等重放
        mockMvc.perform(post("/api/transactions")
                        .header("X-Operator", "teller").header("X-Roles", "ANALYST")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("rest-1"));

        // 非法金额
        String bad = body.replace("rest-1", "rest-bad").replace("\"amount\":10", "\"amount\":-1");
        mockMvc.perform(post("/api/transactions")
                        .header("X-Operator", "teller").header("X-Roles", "ANALYST")
                        .contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AMOUNT_NOT_POSITIVE"));
    }

    @Test
    void review_override_requires_senior_role_over_http() throws Exception {
        log.info("覆盖业务[REST 越权] 用例: 高级复核接口上 ANALYST 覆盖结论 -> 403");
        // 先制造一笔 REVIEW 交易（大额 60000 = 45 分）
        long time = System.currentTimeMillis();
        String txnBody = "{\"requestId\":\"rest-rev-1\",\"accountId\":\"AREV\",\"targetId\":\"TREV\","
                + "\"amount\":60000,\"currency\":\"CNY\",\"txnTimeEpochMilli\":" + time + "}";
        String submitResp = mockMvc.perform(post("/api/transactions")
                        .header("X-Operator", "teller").header("X-Roles", "ANALYST")
                        .contentType(MediaType.APPLICATION_JSON).content(txnBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evaluation.disposition").value("REVIEW"))
                .andReturn().getResponse().getContentAsString();
        String txnId = MAPPER.readTree(submitResp).get("txnId").asText();

        String listJson = mockMvc.perform(get("/api/reviews"))
                .andReturn().getResponse().getContentAsString();
        JsonNode reviews = MAPPER.readTree(listJson);
        String reviewId = null;
        for (JsonNode n : reviews) {
            if (n.get("txnId").asText().equals(txnId)) {
                reviewId = n.get("reviewId").asText();
                break;
            }
        }
        org.junit.jupiter.api.Assertions.assertNotNull(reviewId, "应为该 REVIEW 交易自动创建复核单");

        mockMvc.perform(post("/api/reviews/" + reviewId + "/claim")
                        .header("X-Operator", "junior").header("X-Roles", "ANALYST"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/reviews/" + reviewId + "/resolve")
                        .header("X-Operator", "junior").header("X-Roles", "ANALYST")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"APPROVE\",\"comment\":\"放行\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }
}
