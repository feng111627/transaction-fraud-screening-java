package com.github.feng111627.transactionfraudscreeningjava.tests;

import com.github.feng111627.transactionfraudscreeningjava.domain.AuditEvent;
import com.github.feng111627.transactionfraudscreeningjava.domain.Decision;
import com.github.feng111627.transactionfraudscreeningjava.domain.Role;
import com.github.feng111627.transactionfraudscreeningjava.dto.IngestResult;
import com.github.feng111627.transactionfraudscreeningjava.dto.PolicyPublishRequest;
import com.github.feng111627.transactionfraudscreeningjava.dto.ReplayResult;
import com.github.feng111627.transactionfraudscreeningjava.dto.TransactionRequest;
import com.github.feng111627.transactionfraudscreeningjava.exception.ApiException;
import com.github.feng111627.transactionfraudscreeningjava.rules.AmountRule;
import com.github.feng111627.transactionfraudscreeningjava.rules.RiskRuleEngine;
import com.github.feng111627.transactionfraudscreeningjava.rules.RuleConfig;
import com.github.feng111627.transactionfraudscreeningjava.rules.VelocityRule;
import com.github.feng111627.transactionfraudscreeningjava.rules.WatchlistRule;
import com.github.feng111627.transactionfraudscreeningjava.service.PolicyService;
import com.github.feng111627.transactionfraudscreeningjava.service.ReplayService;
import com.github.feng111627.transactionfraudscreeningjava.service.TransactionService;
import com.github.feng111627.transactionfraudscreeningjava.store.AuditLog;
import com.github.feng111627.transactionfraudscreeningjava.store.PolicyStore;
import com.github.feng111627.transactionfraudscreeningjava.store.ReviewStore;
import com.github.feng111627.transactionfraudscreeningjava.store.TransactionStore;
import com.github.feng111627.transactionfraudscreeningjava.store.WatchlistStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("决策重算：按当时策略与名单状态重算并核对")
@ExtendWith(CaseResultLogger.class)
class ReplayServiceTest {

    private TransactionStore transactionStore;
    private WatchlistStore watchlistStore;
    private ReviewStore reviewStore;
    private AuditLog auditLog;
    private PolicyService policyService;
    private TransactionService transactionService;
    private ReplayService replayService;

    @BeforeEach
    void setUp() {
        transactionStore = new TransactionStore();
        watchlistStore = new WatchlistStore();
        reviewStore = new ReviewStore();
        auditLog = new AuditLog();
        RiskRuleEngine ruleEngine = new RiskRuleEngine(
                List.of(new AmountRule(), new VelocityRule(), new WatchlistRule()));
        PolicyStore policyStore = new PolicyStore();
        policyService = new PolicyService(policyStore, ruleEngine, auditLog);
        transactionService = new TransactionService(transactionStore, watchlistStore,
                reviewStore, auditLog, ruleEngine);
        replayService = new ReplayService(transactionStore, watchlistStore,
                policyStore, ruleEngine, auditLog);
    }

    private TransactionRequest tx(String txId, String accountId, String amount) {
        return new TransactionRequest(txId, accountId, new BigDecimal(amount),
                "CNY", "M-1", "APP", Instant.now().toString());
    }

    @Test
    @DisplayName("名单命中交易：名单变更后重算仍按当时名单状态，结果与原始结论一致")
    void replayConsistentAfterWatchlistChange() {
        watchlistStore.add("ACC-R1", "涉诈账户");
        IngestResult original = transactionService.ingest(tx("TX-RP1", "ACC-R1", "100"));
        assertEquals(Decision.REVIEW, original.assessment().decision());
        assertEquals("rules:v1/watchlist:v1", original.assessment().ruleSetVersion());

        // 之后名单被移除（当前名单已不含该账户）
        watchlistStore.remove("ACC-R1");

        ReplayResult result = replayService.recompute("TX-RP1", "aud1");
        assertTrue(result.consistent());
        assertEquals(original.assessment().totalScore(), result.recomputed().totalScore());
        assertEquals(original.assessment().decision(), result.recomputed().decision());
        assertEquals(original.assessment().hits(), result.recomputed().hits());
        // 重算用的是当时的名单版本 v1，不是当前的 v2
        assertEquals("rules:v1/watchlist:v1", result.recomputed().ruleSetVersion());
    }

    @Test
    @DisplayName("频率命中交易：后续同账户交易不影响重算，窗口计数按当时入账集合还原")
    void replayConsistentForVelocityHit() {
        // 同账户连发 6 笔，第 6 笔判定时窗口内已有 5 笔 -> 命中 VELOCITY_HIGH(30)
        for (int i = 1; i <= 6; i++) {
            transactionService.ingest(tx("TX-V" + i, "ACC-V", "100"));
        }
        var sixth = transactionStore.findById("TX-V6").orElseThrow();
        assertEquals(30, sixth.assessment().totalScore());
        assertEquals("VELOCITY_HIGH", sixth.assessment().hits().get(0).ruleId());

        // 再入账 10 笔，当前窗口内已有 16 笔
        for (int i = 7; i <= 16; i++) {
            transactionService.ingest(tx("TX-V" + i, "ACC-V", "100"));
        }

        // 重算第 6 笔：仍只按当时已入账的 5 笔计算，结果一致
        ReplayResult result = replayService.recompute("TX-V6", "aud1");
        assertTrue(result.consistent());
        assertEquals(30, result.recomputed().totalScore());
        assertEquals(sixth.assessment().hits(), result.recomputed().hits());
    }

    @Test
    @DisplayName("策略发布后重算老交易：按当时生效的旧版本重算，结果一致")
    void replayConsistentAfterPolicyPublish() {
        IngestResult original = transactionService.ingest(tx("TX-RP2", "ACC-R2", "15000"));
        assertEquals(40, original.assessment().totalScore());

        // 发布 v2：大额阈值提到 100000，当前策略下 15000 不再命中
        policyService.publish(new PolicyPublishRequest("ops1", Role.POLICY_ADMIN, "提阈值",
                new RuleConfig("v2", new BigDecimal("100000"), 40, 60, 5, 30, 50, 50, 80)));

        ReplayResult result = replayService.recompute("TX-RP2", "aud1");
        assertTrue(result.consistent());
        assertEquals(40, result.recomputed().totalScore());
        assertEquals("rules:v1/watchlist:v0", result.recomputed().ruleSetVersion());
        assertEquals(original.assessment().hits(), result.recomputed().hits());
    }

    @Test
    @DisplayName("重算是只读核对：不新增交易、不重复开工单、不覆盖原结论，只追加审计")
    void replayHasNoSideEffects() {
        watchlistStore.add("ACC-R3", "涉诈账户");
        transactionService.ingest(tx("TX-RP3", "ACC-R3", "100")); // REVIEW -> 自动开工单
        int txCountBefore = transactionStore.findAll().size();
        int caseCountBefore = reviewStore.findAll().size();
        var assessmentBefore = transactionStore.findById("TX-RP3").orElseThrow().assessment();

        replayService.recompute("TX-RP3", "aud1");
        replayService.recompute("TX-RP3", "aud2");

        assertEquals(txCountBefore, transactionStore.findAll().size());
        assertEquals(caseCountBefore, reviewStore.findAll().size());
        assertSame(assessmentBefore, transactionStore.findById("TX-RP3").orElseThrow().assessment());
        // 审计只追加了 2 条 DECISION_REPLAY，且都记录一致
        List<AuditEvent> replays = auditLog.events().stream()
                .filter(e -> e.action().equals("DECISION_REPLAY")).toList();
        assertEquals(2, replays.size());
        assertTrue(replays.stream().allMatch(e -> e.details().contains("consistent=true")));
        assertEquals("aud1", replays.get(0).actor());
        assertEquals("TX-RP3", replays.get(0).subject());
        assertTrue(auditLog.verify());
    }

    @Test
    @DisplayName("重算留痕可查：操作者、交易、结果一致性都进入审计")
    void replayIsAudited() {
        transactionService.ingest(tx("TX-RP4", "ACC-R4", "100"));
        replayService.recompute("TX-RP4", "auditor-9");

        AuditEvent event = auditLog.events().stream()
                .filter(e -> e.action().equals("DECISION_REPLAY"))
                .findFirst().orElseThrow();
        assertEquals("auditor-9", event.actor());
        assertEquals("TX-RP4", event.subject());
        assertTrue(event.details().contains("consistent=true"));
        assertNotNull(event.at());
        assertTrue(auditLog.verify());
    }

    @Test
    @DisplayName("重算不存在的交易返回 404，operator 为空返回 400")
    void replayValidation() {
        ApiException e1 = assertThrows(ApiException.class,
                () -> replayService.recompute("TX-NOPE", "aud1"));
        assertEquals(HttpStatus.NOT_FOUND, e1.status());

        ApiException e2 = assertThrows(ApiException.class,
                () -> replayService.recompute("TX-NOPE", " "));
        assertEquals(HttpStatus.BAD_REQUEST, e2.status());
    }
}
