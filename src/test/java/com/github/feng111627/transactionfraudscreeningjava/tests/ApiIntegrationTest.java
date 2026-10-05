package com.github.feng111627.transactionfraudscreeningjava.tests;

import com.github.feng111627.transactionfraudscreeningjava.store.AuditLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("HTTP 入口：单笔/批量接入、复核、审计端点")
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(CaseResultLogger.class)
class ApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuditLog auditLog;

    private static String txJson(String id, String account, String amount) {
        return """
                {"transactionId":"%s","accountId":"%s","amount":%s,
                 "currency":"CNY","merchantId":"M-1","channel":"APP",
                 "occurredAt":"2026-10-06T12:00:00Z"}
                """.formatted(id, account, amount);
    }

    @Test
    @DisplayName("单笔接入返回结论，重复提交返回 DUPLICATE 且结论一致")
    void singleIngestAndIdempotency() throws Exception {
        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON).content(txJson("API-T1", "API-A1", "100")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.assessment.decision").value("APPROVE"));

        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON).content(txJson("API-T1", "API-A1", "100")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DUPLICATE"))
                .andExpect(jsonPath("$.assessment.decision").value("APPROVE"));
    }

    @Test
    @DisplayName("字段缺失的交易被明确拒绝并给出原因")
    void rejectInvalidTransaction() throws Exception {
        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionId\":\"API-T2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectReason").isNotEmpty());
    }

    @Test
    @DisplayName("批量接入逐笔返回结论")
    void batchIngest() throws Exception {
        String batch = "[" + txJson("API-T3", "API-A2", "100") + ","
                + txJson("API-T3", "API-A2", "100") + ","
                + txJson("API-T4", "API-A2", "20000") + "]";
        mockMvc.perform(post("/api/transactions/batch")
                        .contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$[1].status").value("DUPLICATE"))
                .andExpect(jsonPath("$[2].status").value("ACCEPTED"))
                .andExpect(jsonPath("$[2].assessment.hits[0].ruleId").value("AMOUNT_HIGH"));
    }

    @Test
    @DisplayName("名单账户交易触发 REVIEW 并自动开工单，复核全流程可走通")
    void reviewFlowOverHttp() throws Exception {
        mockMvc.perform(post("/api/watchlist/API-A3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"涉诈账户\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON).content(txJson("API-T5", "API-A3", "100")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assessment.decision").value("REVIEW"));

        MvcResult list = mockMvc.perform(get("/api/reviews"))
                .andExpect(status().isOk()).andReturn();
        String body = list.getResponse().getContentAsString();
        String caseId = body.split("\"caseId\":\"")[1].split("\"")[0];

        String action = """
                {"operator":"sup1","role":"SUPERVISOR","targetStatus":"%s","note":"ok"}
                """;
        mockMvc.perform(post("/api/reviews/" + caseId + "/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(action.formatted("IN_PROGRESS")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        mockMvc.perform(post("/api/reviews/" + caseId + "/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(action.formatted("RESOLVED_BLOCK")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED_BLOCK"));
    }

    @Test
    @DisplayName("越权复核返回 403，非法状态迁移返回 409")
    void unauthorizedAndIllegalTransitionOverHttp() throws Exception {
        mockMvc.perform(post("/api/watchlist/API-A4")
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"测试\"}"));
        mockMvc.perform(post("/api/transactions")
                .contentType(MediaType.APPLICATION_JSON).content(txJson("API-T6", "API-A4", "100")));
        MvcResult list = mockMvc.perform(get("/api/reviews")).andReturn();
        String body = list.getResponse().getContentAsString();
        // 找到 API-T6 对应工单
        String caseId = null;
        for (String chunk : body.split("\\{")) {
            if (chunk.contains("API-T6")) {
                caseId = chunk.split("\"caseId\":\"")[1].split("\"")[0];
            }
        }

        // AUDITOR 越权
        mockMvc.perform(post("/api/reviews/" + caseId + "/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"aud1\",\"role\":\"AUDITOR\",\"targetStatus\":\"IN_PROGRESS\"}"))
                .andExpect(status().isForbidden());
        // 非法迁移 OPEN -> CLOSED
        mockMvc.perform(post("/api/reviews/" + caseId + "/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"sup1\",\"role\":\"SUPERVISOR\",\"targetStatus\":\"CLOSED\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("审计端点可查询事件且哈希链校验通过")
    void auditEndpoint() throws Exception {
        mockMvc.perform(get("/api/audit/verify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intact").value(true));
        assertTrue(auditLog.verify());
    }
}
