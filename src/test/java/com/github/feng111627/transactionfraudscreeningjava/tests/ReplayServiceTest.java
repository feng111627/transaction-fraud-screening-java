package com.github.feng111627.transactionfraudscreeningjava.tests;

import com.github.feng111627.transactionfraudscreeningjava.domain.Decision;
import com.github.feng111627.transactionfraudscreeningjava.domain.Role;
import com.github.feng111627.transactionfraudscreeningjava.dto.IngestResult;
import com.github.feng111627.transactionfraudscreeningjava.dto.PolicySpecRequest;
import com.github.feng111627.transactionfraudscreeningjava.dto.ReplayRequest;
import com.github.feng111627.transactionfraudscreeningjava.dto.ReplayResult;
import com.github.feng111627.transactionfraudscreeningjava.dto.TransactionRequest;
import com.github.feng111627.transactionfraudscreeningjava.exception.ApiException;
import com.github.feng111627.transactionfraudscreeningjava.rules.AmountRule;
import com.github.feng111627.transactionfraudscreeningjava.rules.RiskRuleEngine;
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

@DisplayName("历史重算：按当时策略与名单状态核对，只读无副作用")
@ExtendWith(CaseResultLogger.class)
class ReplayServiceTest {

    private TransactionStore transactionStore;
    private WatchlistStore watchlistStore;
    private ReviewStore reviewStore;
    private AuditLog auditLog;
    private PolicyStore policyStore;
    private PolicyService policyService;
    private TransactionService transactionService;
    private ReplayService replayService;

    @BeforeEach
    void setUp() {
        transactionStore = new TransactionStore();
        watchlistStore = new WatchlistStore();
        reviewStore = new ReviewStore();
        auditLog = new AuditLog();
        policyStore = new PolicyStore();
        RiskRuleEngine ruleEngine = new RiskRuleEngine(
                List.of(new AmountRule(), new VelocityRule(), new WatchlistRule()));
        policyService = new PolicyService(policyStore, ruleEngine, auditLog);
        transactionService = new TransactionService(transactionStore, watchlistStore,
                reviewStore, auditLog, ruleEngine);
        replayService = new ReplayService(transactionStore, policyStore, ruleEngine,
                policyService, auditLog);
    }

    private TransactionRequest tx(String id, String accountId, String amount) {
        return new TransactionRequest(id, accountId, new BigDecimal(amount),
                "CNY", "M-1", "APP", Instant.now().toString());
    }

    @Test
    @DisplayName("重算结果与原始结论完全一致（分数/命中规则/结论）")
    void replayMatchesOriginal() {
        IngestResult ingested = transactionService.ingest(tx("R-T1", "A1", "15000"));
        ReplayResult result = replayService.replay("R-T1", new ReplayRequest("aud1", Role.AUDITOR));

        assertTrue(result.consistent());
        assertEquals(ingested.assessment().totalScore(), result.replayedScore());
        assertEquals(ingested.assessment().decision(), result.replayedDecision());
        assertEquals(List.of("AMOUNT_HIGH"), result.originalHits());
        assertEquals(result.originalHits(), result.replayedHits());
        assertEquals("v1", result.policyVersion());
        assertEquals("aud1", result.operator());
        assertNotNull(result.replayedAt());
    }

    @Test
    @DisplayName("策略发布新版本、名单变更后，重算仍按当时版本与当时名单状态得到一致结果")
    void replayConsistentAfterPolicyAndWatchlistChange() {
        // 当时：v1 + 账户在名单中 -> 50+40=90 -> BLOCK
        watchlistStore.add("A-BAD", "涉诈账户");
        IngestResult ingested = transactionService.ingest(tx("R-T2", "A-BAD", "15000"));
        assertEquals(90, ingested.assessment().totalScore());
        assertEquals(Decision.BLOCK, ingested.assessment().decision());

        // 之后：发布 v2（大额阈值抬高、名单分值降低），并把账户移出名单
        policyService.publish(new PolicySpecRequest("ops1", Role.POLICY_ADMIN, "v2",
                new BigDecimal("100000"), 40, 60, 5, 30, 10, 50, 80));
        watchlistStore.remove("A-BAD");

        ReplayResult result = replayService.replay("R-T2", new ReplayRequest("sup1", Role.SUPERVISOR));
        assertTrue(result.consistent());
        assertEquals(90, result.replayedScore());
        assertEquals(Decision.BLOCK, result.replayedDecision());
        assertEquals(List.of("AMOUNT_HIGH", "WATCHLIST_HIT"), result.replayedHits());
        assertEquals("v1", result.policyVersion());
        assertEquals(1, result.watchlistVersion());
    }

    @Test
    @DisplayName("重算只读：不新增计数、不重复开工单、不覆盖原结论，仅追加审计")
    void replayHasNoSideEffects() {
        transactionService.ingest(tx("R-T3", "A2", "15000"));
        transactionService.ingest(tx("R-T4", "A2", "20000"));
        int txCountBefore = transactionStore.findAll().size();
        int caseCountBefore = reviewStore.findAll().size();
        var assessmentBefore = transactionStore.findById("R-T3").orElseThrow().assessment();
        int auditBefore = auditLog.events().size();

        replayService.replay("R-T3", new ReplayRequest("aud1", Role.AUDITOR));
        replayService.replay("R-T3", new ReplayRequest("aud1", Role.AUDITOR));

        assertEquals(txCountBefore, transactionStore.findAll().size());
        assertEquals(caseCountBefore, reviewStore.findAll().size());
        assertSame(assessmentBefore, transactionStore.findById("R-T3").orElseThrow().assessment());
        // 两次重算各留一条审计，且哈希链完整
        assertEquals(auditBefore + 2, auditLog.events().size());
        assertTrue(auditLog.verify());
    }

    @Test
    @DisplayName("重算留痕：操作者、目标交易、一致性结果均可审计")
    void replayAudited() {
        transactionService.ingest(tx("R-T5", "A3", "100"));
        replayService.replay("R-T5", new ReplayRequest("aud2", Role.AUDITOR));
        assertTrue(auditLog.events().stream().anyMatch(e ->
                e.action().equals("TRANSACTION_REPLAY")
                        && e.subject().equals("R-T5")
                        && e.actor().equals("aud2")
                        && e.details().contains("consistent=true")));
    }

    @Test
    @DisplayName("重算不存在的交易返回 404，缺少 operator 返回 400")
    void replayInvalidRequests() {
        ApiException notFound = assertThrows(ApiException.class,
                () -> replayService.replay("NOPE", new ReplayRequest("aud1", Role.AUDITOR)));
        assertEquals(HttpStatus.NOT_FOUND, notFound.status());

        transactionService.ingest(tx("R-T6", "A3", "100"));
        ApiException badRequest = assertThrows(ApiException.class,
                () -> replayService.replay("R-T6", new ReplayRequest(null, Role.AUDITOR)));
        assertEquals(HttpStatus.BAD_REQUEST, badRequest.status());
    }
}
