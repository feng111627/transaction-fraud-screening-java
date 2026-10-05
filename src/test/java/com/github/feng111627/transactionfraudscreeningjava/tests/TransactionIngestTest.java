package com.github.feng111627.transactionfraudscreeningjava.tests;

import com.github.feng111627.transactionfraudscreeningjava.domain.Decision;
import com.github.feng111627.transactionfraudscreeningjava.dto.IngestResult;
import com.github.feng111627.transactionfraudscreeningjava.dto.TransactionRequest;
import com.github.feng111627.transactionfraudscreeningjava.rules.AmountRule;
import com.github.feng111627.transactionfraudscreeningjava.rules.RiskRuleEngine;
import com.github.feng111627.transactionfraudscreeningjava.rules.RuleConfig;
import com.github.feng111627.transactionfraudscreeningjava.rules.VelocityRule;
import com.github.feng111627.transactionfraudscreeningjava.rules.WatchlistRule;
import com.github.feng111627.transactionfraudscreeningjava.service.TransactionService;
import com.github.feng111627.transactionfraudscreeningjava.store.AuditLog;
import com.github.feng111627.transactionfraudscreeningjava.store.ReviewStore;
import com.github.feng111627.transactionfraudscreeningjava.store.TransactionStore;
import com.github.feng111627.transactionfraudscreeningjava.store.WatchlistStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("交易接入：校验 / 幂等 / 可解释 / 可复现")
@ExtendWith(CaseResultLogger.class)
class TransactionIngestTest {

    private TransactionStore transactionStore;
    private WatchlistStore watchlistStore;
    private AuditLog auditLog;
    private RiskRuleEngine ruleEngine;
    private TransactionService service;

    @BeforeEach
    void setUp() {
        transactionStore = new TransactionStore();
        watchlistStore = new WatchlistStore();
        ReviewStore reviewStore = new ReviewStore();
        auditLog = new AuditLog();
        ruleEngine = new RiskRuleEngine(List.of(new AmountRule(), new VelocityRule(), new WatchlistRule()));
        service = new TransactionService(transactionStore, watchlistStore, reviewStore, auditLog, ruleEngine);
    }

    private TransactionRequest validRequest(String txId, String accountId, String amount) {
        return new TransactionRequest(txId, accountId, new BigDecimal(amount),
                "CNY", "M-1", "APP", Instant.now().toString());
    }

    @Test
    @DisplayName("正常交易落库并给出 APPROVE 结论")
    void ingestValidTransaction() {
        IngestResult result = service.ingest(validRequest("T1", "A1", "100"));
        assertEquals(IngestResult.IngestStatus.ACCEPTED, result.status());
        assertEquals(Decision.APPROVE, result.assessment().decision());
        assertEquals(1, transactionStore.findAll().size());
    }

    @Test
    @DisplayName("缺少必填字段按具体原因拒绝且不落库")
    void rejectMissingFields() {
        IngestResult result = service.ingest(new TransactionRequest(
                null, null, null, null, null, null, null));
        assertEquals(IngestResult.IngestStatus.REJECTED, result.status());
        assertTrue(result.rejectReason().contains("transactionId"));
        assertTrue(result.rejectReason().contains("accountId"));
        assertTrue(result.rejectReason().contains("amount"));
        assertEquals(0, transactionStore.findAll().size());
    }

    @Test
    @DisplayName("非法金额与非法时间格式分别给出明确拒绝原因")
    void rejectInvalidAmountAndTime() {
        IngestResult badAmount = service.ingest(validRequest("T2", "A1", "-5"));
        assertEquals(IngestResult.IngestStatus.REJECTED, badAmount.status());
        assertTrue(badAmount.rejectReason().contains("金额"));

        TransactionRequest badTime = validRequest("T3", "A1", "10");
        badTime = new TransactionRequest(badTime.transactionId(), badTime.accountId(), badTime.amount(),
                badTime.currency(), badTime.merchantId(), badTime.channel(), "not-a-time");
        IngestResult badTimeResult = service.ingest(badTime);
        assertEquals(IngestResult.IngestStatus.REJECTED, badTimeResult.status());
        assertTrue(badTimeResult.rejectReason().contains("时间"));
        assertEquals(0, transactionStore.findAll().size());
    }

    @Test
    @DisplayName("同一交易重复提交返回首次结论且不重复计数")
    void idempotentResubmission() {
        IngestResult first = service.ingest(validRequest("T4", "A1", "100"));
        IngestResult second = service.ingest(validRequest("T4", "A1", "100"));
        assertEquals(IngestResult.IngestStatus.ACCEPTED, first.status());
        assertEquals(IngestResult.IngestStatus.DUPLICATE, second.status());
        assertSame(first.assessment(), second.assessment());
        assertEquals(1, transactionStore.findAll().size());
    }

    @Test
    @DisplayName("批量提交逐笔给出结论，重复笔不重复入账")
    void batchIngest() {
        List<IngestResult> results = service.ingestBatch(List.of(
                validRequest("T5", "A1", "100"),
                validRequest("T5", "A1", "100"),
                validRequest("T6", "A1", "20000")));
        assertEquals(IngestResult.IngestStatus.ACCEPTED, results.get(0).status());
        assertEquals(IngestResult.IngestStatus.DUPLICATE, results.get(1).status());
        assertEquals(IngestResult.IngestStatus.ACCEPTED, results.get(2).status());
        assertEquals(2, transactionStore.findAll().size());
    }

    @Test
    @DisplayName("大额交易命中 AMOUNT_HIGH 规则并说明原因")
    void explainableAmountHit() {
        IngestResult result = service.ingest(validRequest("T7", "A1", "15000"));
        assertEquals(40, result.assessment().totalScore());
        assertEquals(1, result.assessment().hits().size());
        assertEquals("AMOUNT_HIGH", result.assessment().hits().get(0).ruleId());
        assertTrue(result.assessment().hits().get(0).reason().contains("15000"));
    }

    @Test
    @DisplayName("命中名单加分并记录名单版本")
    void watchlistHit() {
        watchlistStore.add("A-BAD", "涉诈账户");
        IngestResult result = service.ingest(validRequest("T8", "A-BAD", "100"));
        assertEquals(50, result.assessment().totalScore());
        assertEquals(Decision.REVIEW, result.assessment().decision());
        assertTrue(result.assessment().ruleSetVersion().contains("watchlist:v1"));
    }

    @Test
    @DisplayName("规则阈值更新后历史结论仍按旧版本复现")
    void reproducibleAfterConfigChange() {
        IngestResult before = service.ingest(validRequest("T9", "A1", "15000"));
        assertEquals("rules:v1/watchlist:v0", before.assessment().ruleSetVersion());

        ruleEngine.updateConfig(new RuleConfig("v2", new BigDecimal("100000"),
                40, 60, 5, 30, 50, 50, 80));

        // 重复提交返回的仍是旧结论
        IngestResult replay = service.ingest(validRequest("T9", "A1", "15000"));
        assertEquals(IngestResult.IngestStatus.DUPLICATE, replay.status());
        assertEquals(before.assessment(), replay.assessment());
        // 账本中的历史结论未被新配置改写
        assertEquals("rules:v1/watchlist:v0",
                transactionStore.findById("T9").orElseThrow().assessment().ruleSetVersion());
        // 新交易按新配置判定：15000 不再命中大额规则
        IngestResult after = service.ingest(validRequest("T10", "A1", "15000"));
        assertEquals(0, after.assessment().totalScore());
        assertEquals("rules:v2/watchlist:v0", after.assessment().ruleSetVersion());
    }

    @Test
    @DisplayName("接入与拒绝均写入审计且哈希链完整")
    void auditTrailIntact() {
        service.ingest(validRequest("T11", "A1", "100"));
        service.ingest(validRequest("T11", "A1", "100"));
        service.ingest(new TransactionRequest(null, null, null, null, null, null, null));
        assertTrue(auditLog.verify());
        assertEquals(3, auditLog.events().size());
    }
}
