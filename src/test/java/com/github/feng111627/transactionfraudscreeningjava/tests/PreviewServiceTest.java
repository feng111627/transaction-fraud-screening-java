package com.github.feng111627.transactionfraudscreeningjava.tests;

import com.github.feng111627.transactionfraudscreeningjava.domain.AuditEvent;
import com.github.feng111627.transactionfraudscreeningjava.domain.Decision;
import com.github.feng111627.transactionfraudscreeningjava.domain.Role;
import com.github.feng111627.transactionfraudscreeningjava.dto.IngestResult;
import com.github.feng111627.transactionfraudscreeningjava.dto.PreviewRequest;
import com.github.feng111627.transactionfraudscreeningjava.dto.PreviewResult;
import com.github.feng111627.transactionfraudscreeningjava.dto.TransactionRequest;
import com.github.feng111627.transactionfraudscreeningjava.exception.ApiException;
import com.github.feng111627.transactionfraudscreeningjava.rules.AmountRule;
import com.github.feng111627.transactionfraudscreeningjava.rules.RiskRuleEngine;
import com.github.feng111627.transactionfraudscreeningjava.rules.RuleConfig;
import com.github.feng111627.transactionfraudscreeningjava.rules.VelocityRule;
import com.github.feng111627.transactionfraudscreeningjava.rules.WatchlistRule;
import com.github.feng111627.transactionfraudscreeningjava.service.PolicyService;
import com.github.feng111627.transactionfraudscreeningjava.service.PreviewService;
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

@DisplayName("影响预演：候选策略对历史交易只读试算")
@ExtendWith(CaseResultLogger.class)
class PreviewServiceTest {

    private TransactionStore transactionStore;
    private ReviewStore reviewStore;
    private AuditLog auditLog;
    private RiskRuleEngine ruleEngine;
    private PolicyService policyService;
    private TransactionService transactionService;
    private PreviewService previewService;

    @BeforeEach
    void setUp() {
        transactionStore = new TransactionStore();
        WatchlistStore watchlistStore = new WatchlistStore();
        reviewStore = new ReviewStore();
        auditLog = new AuditLog();
        ruleEngine = new RiskRuleEngine(List.of(new AmountRule(), new VelocityRule(), new WatchlistRule()));
        PolicyStore policyStore = new PolicyStore();
        policyService = new PolicyService(policyStore, ruleEngine, auditLog);
        transactionService = new TransactionService(transactionStore, watchlistStore,
                reviewStore, auditLog, ruleEngine);
        ReplayService replayService = new ReplayService(transactionStore, watchlistStore,
                policyStore, ruleEngine, auditLog);
        previewService = new PreviewService(transactionStore, watchlistStore, replayService, policyService);
    }

    private TransactionRequest tx(String txId, String accountId, String amount) {
        return new TransactionRequest(txId, accountId, new BigDecimal(amount),
                "CNY", "M-1", "APP", Instant.now().toString());
    }

    /** 候选策略：reviewThreshold 从 50 降到 30，其余与 v1 相同。 */
    private RuleConfig candidate() {
        return new RuleConfig("v-candidate", new BigDecimal("10000"), 40, 60, 5, 30, 50, 30, 80);
    }

    private void seedTransactions() {
        // v1 下：40 分 APPROVE / 0 分 APPROVE / 40 分 APPROVE
        transactionService.ingest(tx("TX-S1", "ACC-S1", "15000"));
        transactionService.ingest(tx("TX-S2", "ACC-S2", "100"));
        transactionService.ingest(tx("TX-S3", "ACC-S3", "90000"));
    }

    @Test
    @DisplayName("预演正确统计结论变化数并给出典型样本")
    void previewCountsChangesAndSamples() {
        seedTransactions();

        PreviewResult result = previewService.preview(
                new PreviewRequest("ops1", Role.POLICY_ADMIN, candidate()));

        assertEquals("v-candidate", result.candidateVersion());
        assertEquals(3, result.totalTransactions());
        // 40 分的两笔在新阈值 30 下变为 REVIEW，0 分的一笔不变
        assertEquals(2, result.changedCount());
        assertEquals(1, result.unchangedCount());
        assertEquals(2, result.samples().size());
        for (PreviewResult.DecisionChange sample : result.samples()) {
            assertEquals(Decision.APPROVE, sample.fromDecision());
            assertEquals(Decision.REVIEW, sample.toDecision());
            assertEquals(40, sample.fromScore());
            assertEquals(40, sample.toScore());
        }
        assertTrue(result.samples().stream().map(PreviewResult.DecisionChange::transactionId)
                .toList().containsAll(List.of("TX-S1", "TX-S3")));
    }

    @Test
    @DisplayName("预演纯只读：不落库、不计数、不开工单、不写审计、不改当前生效策略")
    void previewIsReadOnly() {
        seedTransactions();
        int txBefore = transactionStore.findAll().size();
        int caseBefore = reviewStore.findAll().size();
        int auditBefore = auditLog.events().size();
        String activeBefore = policyService.active().version();

        previewService.preview(new PreviewRequest("ops1", Role.POLICY_ADMIN, candidate()));

        assertEquals(txBefore, transactionStore.findAll().size());
        assertEquals(caseBefore, reviewStore.findAll().size());
        assertEquals(auditBefore, auditLog.events().size());
        assertEquals(activeBefore, policyService.active().version());
        assertEquals("v1", ruleEngine.currentConfig().version());
        // 历史交易的结论也未被触碰
        assertEquals(Decision.APPROVE,
                transactionStore.findById("TX-S1").orElseThrow().assessment().decision());
        assertTrue(auditLog.verify());
    }

    @Test
    @DisplayName("候选策略未发布前对实时判定无任何影响")
    void candidateDoesNotAffectLiveDecisions() {
        seedTransactions();
        previewService.preview(new PreviewRequest("ops1", Role.POLICY_ADMIN, candidate()));

        // 预演后新交易仍按当前生效的 v1 判定（40 分 APPROVE，而非候选策略的 REVIEW）
        IngestResult live = transactionService.ingest(tx("TX-S4", "ACC-S4", "15000"));
        assertEquals("rules:v1/watchlist:v0", live.assessment().ruleSetVersion());
        assertEquals(Decision.APPROVE, live.assessment().decision());
        // 候选版本没有进入版本注册表
        assertEquals(1, policyService.list().size());
        assertEquals("v1", policyService.active().version());
    }

    @Test
    @DisplayName("越权预演被拒绝、留痕且无副作用")
    void unauthorizedPreviewRejected() {
        seedTransactions();
        int auditBefore = auditLog.events().size();

        ApiException e = assertThrows(ApiException.class, () ->
                previewService.preview(new PreviewRequest("ana1", Role.ANALYST, candidate())));
        assertEquals(HttpStatus.FORBIDDEN, e.status());

        // 留痕：权限拒绝事件；无副作用：生效策略与实时判定不变
        assertTrue(auditLog.events().stream().skip(auditBefore).anyMatch(ev ->
                ev.action().equals("POLICY_PERMISSION_DENIED") && ev.actor().equals("ana1")));
        assertEquals("v1", policyService.active().version());
        IngestResult live = transactionService.ingest(tx("TX-S5", "ACC-S5", "15000"));
        assertEquals(Decision.APPROVE, live.assessment().decision());
        assertTrue(auditLog.verify());
    }

    @Test
    @DisplayName("非法候选配置被拒绝（400）")
    void invalidCandidateRejected() {
        seedTransactions();
        RuleConfig bad = new RuleConfig("v-bad", new BigDecimal("10000"), 40, 60, 5, 30, 50, 90, 80);
        ApiException e = assertThrows(ApiException.class, () ->
                previewService.preview(new PreviewRequest("ops1", Role.POLICY_ADMIN, bad)));
        assertEquals(HttpStatus.BAD_REQUEST, e.status());
        assertEquals("v1", policyService.active().version());
    }

    @Test
    @DisplayName("预演留痕边界：成功的预演本身不产生任何审计事件")
    void successfulPreviewWritesNoAudit() {
        seedTransactions();
        List<AuditEvent> before = auditLog.events();
        previewService.preview(new PreviewRequest("ops1", Role.POLICY_ADMIN, candidate()));
        assertEquals(before.size(), auditLog.events().size());
    }
}
