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
                        .content("{\"reason\":\"涉诈账户\",\"operator\":\"ops1\",\"role\":\"POLICY_ADMIN\"}"))
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
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"测试\",\"operator\":\"ops1\",\"role\":\"POLICY_ADMIN\"}"));
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

    @Test
    @DisplayName("策略发布/预演/重算端点：管理员可发布并切换，越权 403，重算一致")
    void policyOpsOverHttp() throws Exception {
        String spec = """
                {"operator":"%s","role":"%s","version":"%s","highAmountThreshold":100000,
                 "highAmountScore":40,"velocityWindowSeconds":60,"velocityMaxCount":5,
                 "velocityScore":30,"watchlistScore":50,"reviewThreshold":50,"blockThreshold":80}
                """;
        // 越权发布 -> 403
        mockMvc.perform(post("/api/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(spec.formatted("ana1", "ANALYST", "HTTP-V2")))
                .andExpect(status().isForbidden());
        // 管理员发布 -> 成功并记录发布人
        mockMvc.perform(post("/api/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(spec.formatted("ops1", "POLICY_ADMIN", "HTTP-V2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("HTTP-V2"))
                .andExpect(jsonPath("$.publishedBy").value("ops1"));
        // 重复发布同版本 -> 409
        mockMvc.perform(post("/api/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(spec.formatted("ops1", "POLICY_ADMIN", "HTTP-V2")))
                .andExpect(status().isConflict());
        // 生效版本已切换
        mockMvc.perform(get("/api/policies/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("HTTP-V2"));

        // 新交易按新版本判定：15000 不再命中大额规则
        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON).content(txJson("API-T7", "API-A5", "15000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assessment.totalScore").value(0))
                .andExpect(jsonPath("$.assessment.policyVersion").value("HTTP-V2"));

        // 重算：与原始结论一致
        mockMvc.perform(post("/api/transactions/API-T7/replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"aud1\",\"role\":\"AUDITOR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistent").value(true))
                .andExpect(jsonPath("$.policyVersion").value("HTTP-V2"));

        // 预演：只读，候选版本不生效
        mockMvc.perform(post("/api/policies/dry-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(spec.formatted("ops1", "POLICY_ADMIN", "HTTP-V3-DRY")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidateVersion").value("HTTP-V3-DRY"));
        mockMvc.perform(get("/api/policies/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("HTTP-V2"));

        // 名单越权 -> 403
        mockMvc.perform(post("/api/watchlist/API-A6")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"测试\",\"operator\":\"ana1\",\"role\":\"ANALYST\"}"))
                .andExpect(status().isForbidden());
        assertTrue(auditLog.verify());
    }
}
