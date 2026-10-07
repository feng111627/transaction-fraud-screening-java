package com.github.feng111627.transactionfraudscreeningjava.tests;

import com.github.feng111627.transactionfraudscreeningjava.domain.Decision;
import com.github.feng111627.transactionfraudscreeningjava.domain.Role;
import com.github.feng111627.transactionfraudscreeningjava.dto.DryRunResult;
import com.github.feng111627.transactionfraudscreeningjava.dto.IngestResult;
import com.github.feng111627.transactionfraudscreeningjava.dto.PolicySpecRequest;
import com.github.feng111627.transactionfraudscreeningjava.dto.TransactionRequest;
import com.github.feng111627.transactionfraudscreeningjava.exception.ApiException;
import com.github.feng111627.transactionfraudscreeningjava.rules.AmountRule;
import com.github.feng111627.transactionfraudscreeningjava.rules.RiskRuleEngine;
import com.github.feng111627.transactionfraudscreeningjava.rules.VelocityRule;
import com.github.feng111627.transactionfraudscreeningjava.rules.WatchlistRule;
import com.github.feng111627.transactionfraudscreeningjava.service.DryRunService;
import com.github.feng111627.transactionfraudscreeningjava.service.PolicyService;
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

@DisplayName("影响预演：只读评估候选策略对历史结论的影响")
@ExtendWith(CaseResultLogger.class)
class DryRunServiceTest {

    private TransactionStore transactionStore;
    private ReviewStore reviewStore;
    private AuditLog auditLog;
    private PolicyStore policyStore;
    private TransactionService transactionService;
    private DryRunService dryRunService;

    @BeforeEach
    void setUp() {
        transactionStore = new TransactionStore();
        WatchlistStore watchlistStore = new WatchlistStore();
        reviewStore = new ReviewStore();
        auditLog = new AuditLog();
        policyStore = new PolicyStore();
        RiskRuleEngine ruleEngine = new RiskRuleEngine(
                List.of(new AmountRule(), new VelocityRule(), new WatchlistRule()));
        PolicyService policyService = new PolicyService(policyStore, ruleEngine, auditLog);
        transactionService = new TransactionService(transactionStore, watchlistStore,
                reviewStore, auditLog, ruleEngine);
        dryRunService = new DryRunService(transactionStore, ruleEngine, policyService, auditLog);
        watchlistStore.add("A-BAD", "涉诈账户");
    }

    private TransactionRequest tx(String id, String accountId, String amount) {
        return new TransactionRequest(id, accountId, new BigDecimal(amount),
                "CNY", "M-1", "APP", Instant.now().toString());
    }

    /** 候选策略：大额阈值抬高到 20000，名单分值提高到 80。 */
    private PolicySpecRequest candidate(String version, Role role) {
        return new PolicySpecRequest("ops1", role, version,
                new BigDecimal("20000"), 40, 60, 5, 30, 80, 50, 80);
    }

    private void seedTransactions() {
        transactionService.ingest(tx("D-T1", "A1", "15000"));   // v1: 40 APPROVE
        transactionService.ingest(tx("D-T2", "A1", "100"));     // v1: 0 APPROVE
        transactionService.ingest(tx("D-T3", "A-BAD", "100"));  // v1: 50 REVIEW，开工单
    }

    @Test
    @DisplayName("预演给出结论变化统计与典型样例（从什么结论变成什么结论）")
    void dryRunReportsChanges() {
        seedTransactions();
        DryRunResult result = dryRunService.dryRun(candidate("v2-candidate", Role.POLICY_ADMIN));

        assertEquals(3, result.totalTransactions());
        // D-T1: 15000 不再命中大额 -> 0 分仍 APPROVE（不变）；D-T2 不变；
        // D-T3: 名单 80 分 -> BLOCK（REVIEW -> BLOCK，变化）
        assertEquals(1, result.changedCount());
        assertEquals(2, result.unchangedCount());
        assertEquals(1, result.samples().size());
        DryRunResult.DecisionChange sample = result.samples().get(0);
        assertEquals("D-T3", sample.transactionId());
        assertEquals(Decision.REVIEW, sample.fromDecision());
        assertEquals(Decision.BLOCK, sample.toDecision());
        assertEquals(50, sample.fromScore());
        assertEquals(80, sample.toScore());
        assertEquals("v2-candidate", result.candidateVersion());
        assertEquals("ops1", result.operator());
    }

    @Test
    @DisplayName("预演严格只读：不落库、不计数、不开工单、不切换生效策略，候选版本不进注册表")
    void dryRunIsReadOnly() {
        seedTransactions();
        int txBefore = transactionStore.findAll().size();
        int caseBefore = reviewStore.findAll().size();
        int versionsBefore = policyStore.all().size();
        var assessmentBefore = transactionStore.findById("D-T3").orElseThrow().assessment();

        dryRunService.dryRun(candidate("v2-candidate", Role.POLICY_ADMIN));

        assertEquals(txBefore, transactionStore.findAll().size());
        assertEquals(caseBefore, reviewStore.findAll().size());
        assertSame(assessmentBefore, transactionStore.findById("D-T3").orElseThrow().assessment());
        assertEquals("v1", policyStore.active().version());
        assertEquals(versionsBefore, policyStore.all().size());
        assertTrue(policyStore.find("v2-candidate").isEmpty());
        // 预演后实时判定仍按 v1：15000 命中大额规则
        IngestResult after = transactionService.ingest(tx("D-T4", "A1", "15000"));
        assertEquals(40, after.assessment().totalScore());
        assertEquals("v1", after.assessment().policyVersion());
        assertTrue(auditLog.verify());
    }

    @Test
    @DisplayName("预演留痕：操作者与统计结果写入审计，且哈希链完整")
    void dryRunAudited() {
        seedTransactions();
        dryRunService.dryRun(candidate("v2-candidate", Role.POLICY_ADMIN));
        assertTrue(auditLog.events().stream().anyMatch(e ->
                e.action().equals("POLICY_DRY_RUN")
                        && e.actor().equals("ops1")
                        && e.subject().equals("policy:v2-candidate")
                        && e.details().contains("changed=1")));
        assertTrue(auditLog.verify());
    }

    @Test
    @DisplayName("候选内容非法或缺少 operator 的预演请求被拒绝（400）")
    void dryRunInvalidRequests() {
        PolicySpecRequest noOperator = new PolicySpecRequest(null, Role.POLICY_ADMIN, "vX",
                new BigDecimal("100"), 40, 60, 5, 30, 50, 50, 80);
        ApiException ex = assertThrows(ApiException.class, () -> dryRunService.dryRun(noOperator));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());

        PolicySpecRequest invalid = new PolicySpecRequest("ops1", Role.POLICY_ADMIN, "vX",
                new BigDecimal("-1"), 40, 60, 5, 30, 50, 50, 80);
        ApiException ex2 = assertThrows(ApiException.class, () -> dryRunService.dryRun(invalid));
        assertEquals(HttpStatus.BAD_REQUEST, ex2.status());
        assertTrue(policyStore.find("vX").isEmpty());
    }
}
