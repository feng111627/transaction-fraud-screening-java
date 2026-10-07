package com.github.feng111627.transactionfraudscreeningjava.tests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("策略运营 HTTP 入口：发布 / 切换 / 重算 / 预演 / 越权")
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(CaseResultLogger.class)
class PolicyApiTest {

    @Autowired
    private MockMvc mockMvc;

    private static String txJson(String id, String account, String amount) {
        return """
                {"transactionId":"%s","accountId":"%s","amount":%s,
                 "currency":"CNY","merchantId":"M-1","channel":"APP",
                 "occurredAt":"2026-10-06T12:00:00Z"}
                """.formatted(id, account, amount);
    }

    private static String publishJson(String operator, String role, String version, String threshold) {
        return """
                {"operator":"%s","role":"%s","note":"调阈值",
                 "config":{"version":"%s","highAmountThreshold":%s,"highAmountScore":40,
                           "velocityWindowSeconds":60,"velocityMaxCount":5,"velocityScore":30,
                           "watchlistScore":50,"reviewThreshold":50,"blockThreshold":80}}
                """.formatted(operator, role, version, threshold);
    }

    @Test
    @DisplayName("HTTP：发布后新交易走新版本，历史交易重算结果一致")
    void publishSwitchAndReplayOverHttp() throws Exception {
        // 集成测试共享应用上下文，先生效一个本用例专属的基线版本，不依赖默认 v1
        mockMvc.perform(post("/api/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishJson("ops1", "POLICY_ADMIN", "v-http-base", "10000")))
                .andExpect(status().isOk());

        // 基线版本下一笔 15000 的交易：命中大额 40 分
        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON).content(txJson("API-P1", "API-PA1", "15000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assessment.totalScore").value(40))
                // 共享上下文中名单版本可能已被其他测试推进，只断言策略版本部分
                .andExpect(jsonPath("$.assessment.ruleSetVersion").value(containsString("rules:v-http-base/")));

        // 发布新版本：大额阈值提到 100000
        mockMvc.perform(post("/api/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishJson("ops1", "POLICY_ADMIN", "v-http-2", "100000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedBy").value("ops1"))
                .andExpect(jsonPath("$.config.version").value("v-http-2"));

        mockMvc.perform(get("/api/policies/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.config.version").value("v-http-2"));

        // 新交易按新版本：15000 不再命中
        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON).content(txJson("API-P2", "API-PA2", "15000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assessment.totalScore").value(0))
                .andExpect(jsonPath("$.assessment.ruleSetVersion").value(containsString("rules:v-http-2/")));

        // 历史交易重算：按当时版本，结果一致
        mockMvc.perform(post("/api/transactions/API-P1/replay")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"operator\":\"aud1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistent").value(true))
                .andExpect(jsonPath("$.original.totalScore").value(40))
                .andExpect(jsonPath("$.recomputed.totalScore").value(40));
    }

    @Test
    @DisplayName("HTTP：越权发布 403、同版本重复发布 409、非法配置 400")
    void publishGuardsOverHttp() throws Exception {
        mockMvc.perform(post("/api/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishJson("ana1", "ANALYST", "v-http-3", "100000")))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishJson("ops1", "POLICY_ADMIN", "v-http-3", "100000")))
                .andExpect(status().isOk());
        // 同版本号再发 -> 409
        mockMvc.perform(post("/api/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishJson("ops2", "POLICY_ADMIN", "v-http-3", "1")))
                .andExpect(status().isConflict());

        // 非法配置：reviewThreshold > blockThreshold
        mockMvc.perform(post("/api/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operator":"ops1","role":"POLICY_ADMIN",
                                 "config":{"version":"v-http-bad","highAmountThreshold":10000,"highAmountScore":40,
                                           "velocityWindowSeconds":60,"velocityMaxCount":5,"velocityScore":30,
                                           "watchlistScore":50,"reviewThreshold":90,"blockThreshold":80}}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("HTTP：影响预演返回变化统计，且预演后实时判定不受影响")
    void previewOverHttp() throws Exception {
        // 造两笔历史交易（当前生效策略下 15000 -> 40 分）
        mockMvc.perform(post("/api/transactions")
                .contentType(MediaType.APPLICATION_JSON).content(txJson("API-P3", "API-PA3", "15000")));
        mockMvc.perform(post("/api/transactions")
                .contentType(MediaType.APPLICATION_JSON).content(txJson("API-P4", "API-PA4", "100")));

        // 候选策略：大额阈值 1 元 -> 两笔都会命中(40 分)，结论可能变化
        mockMvc.perform(post("/api/policies/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operator":"ops1","role":"POLICY_ADMIN",
                                 "config":{"version":"v-http-preview","highAmountThreshold":1,"highAmountScore":40,
                                           "velocityWindowSeconds":60,"velocityMaxCount":5,"velocityScore":30,
                                           "watchlistScore":50,"reviewThreshold":50,"blockThreshold":80}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidateVersion").value("v-http-preview"))
                .andExpect(jsonPath("$.totalTransactions").isNumber())
                .andExpect(jsonPath("$.changedCount").isNumber())
                .andExpect(jsonPath("$.samples").isArray());

        // 预演不发布：候选版本不在版本列表中，实时判定不受影响
        mockMvc.perform(get("/api/policies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.config.version == 'v-http-preview')]").isEmpty());

        // 越权预演 -> 403
        mockMvc.perform(post("/api/policies/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operator":"aud1","role":"AUDITOR",
                                 "config":{"version":"v-x","highAmountThreshold":1,"highAmountScore":40,
                                           "velocityWindowSeconds":60,"velocityMaxCount":5,"velocityScore":30,
                                           "watchlistScore":50,"reviewThreshold":50,"blockThreshold":80}}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("HTTP：名单增删需要 POLICY_ADMIN，越权 403")
    void watchlistGuardsOverHttp() throws Exception {
        mockMvc.perform(post("/api/watchlist/API-PW1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"ana1\",\"role\":\"ANALYST\",\"reason\":\"测试\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/watchlist/API-PW1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"ops1\",\"role\":\"POLICY_ADMIN\",\"reason\":\"涉诈\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("ADDED"));

        mockMvc.perform(delete("/api/watchlist/API-PW1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"ops1\",\"role\":\"POLICY_ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("REMOVED"));

        // 再删一次 -> 404
        mockMvc.perform(delete("/api/watchlist/API-PW1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"ops1\",\"role\":\"POLICY_ADMIN\"}"))
                .andExpect(status().isNotFound());
    }
}
