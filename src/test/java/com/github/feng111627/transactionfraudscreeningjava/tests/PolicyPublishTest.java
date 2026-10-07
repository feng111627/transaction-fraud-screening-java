package com.github.feng111627.transactionfraudscreeningjava.tests;

import com.github.feng111627.transactionfraudscreeningjava.domain.AuditEvent;
import com.github.feng111627.transactionfraudscreeningjava.domain.Decision;
import com.github.feng111627.transactionfraudscreeningjava.domain.PolicyVersion;
import com.github.feng111627.transactionfraudscreeningjava.domain.Role;
import com.github.feng111627.transactionfraudscreeningjava.dto.IngestResult;
import com.github.feng111627.transactionfraudscreeningjava.dto.PolicyPublishRequest;
import com.github.feng111627.transactionfraudscreeningjava.dto.TransactionRequest;
import com.github.feng111627.transactionfraudscreeningjava.exception.ApiException;
import com.github.feng111627.transactionfraudscreeningjava.rules.AmountRule;
import com.github.feng111627.transactionfraudscreeningjava.rules.RiskRuleEngine;
import com.github.feng111627.transactionfraudscreeningjava.rules.RuleConfig;
import com.github.feng111627.transactionfraudscreeningjava.rules.VelocityRule;
import com.github.feng111627.transactionfraudscreeningjava.rules.WatchlistRule;
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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("策略版本：发布 / 切换 / 不可变 / 权限 / 并发一致")
@ExtendWith(CaseResultLogger.class)
class PolicyPublishTest {

    private TransactionStore transactionStore;
    private AuditLog auditLog;
    private RiskRuleEngine ruleEngine;
    private PolicyStore policyStore;
    private PolicyService policyService;
    private TransactionService transactionService;

    @BeforeEach
    void setUp() {
        transactionStore = new TransactionStore();
        auditLog = new AuditLog();
        ruleEngine = new RiskRuleEngine(List.of(new AmountRule(), new VelocityRule(), new WatchlistRule()));
        policyStore = new PolicyStore();
        policyService = new PolicyService(policyStore, ruleEngine, auditLog);
        transactionService = new TransactionService(transactionStore, new WatchlistStore(),
                new ReviewStore(), auditLog, ruleEngine);
    }

    private TransactionRequest tx(String txId, String amount) {
        return tx(txId, "ACC-P", amount);
    }

    private TransactionRequest tx(String txId, String accountId, String amount) {
        return new TransactionRequest(txId, accountId, new BigDecimal(amount),
                "CNY", "M-1", "APP", Instant.now().toString());
    }

    /** v2：大额阈值从 10000 提到 100000，其余不变。 */
    private RuleConfig v2Config() {
        return new RuleConfig("v2", new BigDecimal("100000"), 40, 60, 5, 30, 50, 50, 80);
    }

    private PolicyPublishRequest publishRequest(String operator, Role role, RuleConfig config) {
        return new PolicyPublishRequest(operator, role, "调整大额阈值", config);
    }

    @Test
    @DisplayName("发布新版本后新交易按新版本判定，发布前的历史结论不被改写")
    void publishSwitchesNewTransactionsAndKeepsOldConclusions() {
        IngestResult before = transactionService.ingest(tx("TX-P1", "15000"));
        assertEquals(40, before.assessment().totalScore());
        assertEquals("rules:v1/watchlist:v0", before.assessment().ruleSetVersion());

        PolicyVersion v2 = policyService.publish(publishRequest("ops1", Role.POLICY_ADMIN, v2Config()));
        assertEquals("ops1", v2.publishedBy());
        assertNotNull(v2.publishedAt());
        assertEquals("v2", policyService.active().version());

        // 新交易按 v2：15000 不再命中大额规则
        IngestResult after = transactionService.ingest(tx("TX-P2", "15000"));
        assertEquals(0, after.assessment().totalScore());
        assertEquals(Decision.APPROVE, after.assessment().decision());
        assertEquals("rules:v2/watchlist:v0", after.assessment().ruleSetVersion());

        // 历史结论未被改写
        var stored = transactionStore.findById("TX-P1").orElseThrow();
        assertEquals(40, stored.assessment().totalScore());
        assertEquals("rules:v1/watchlist:v0", stored.assessment().ruleSetVersion());
        // 重复提交仍返回首次结论
        IngestResult replay = transactionService.ingest(tx("TX-P1", "15000"));
        assertEquals(IngestResult.IngestStatus.DUPLICATE, replay.status());
        assertEquals(40, replay.assessment().totalScore());

        // 发布行为已留痕
        assertTrue(auditLog.events().stream().anyMatch(e ->
                e.action().equals("POLICY_PUBLISHED") && e.actor().equals("ops1")
                        && e.subject().equals("v2")));
        assertTrue(auditLog.verify());
    }

    @Test
    @DisplayName("已发布版本不可变：同版本号重复发布被拒绝且原版本内容不变")
    void publishedVersionIsImmutable() {
        policyService.publish(publishRequest("ops1", Role.POLICY_ADMIN, v2Config()));

        RuleConfig tampered = new RuleConfig("v2", new BigDecimal("1"), 99, 60, 5, 30, 50, 50, 80);
        ApiException e = assertThrows(ApiException.class,
                () -> policyService.publish(publishRequest("ops2", Role.POLICY_ADMIN, tampered)));
        assertEquals(HttpStatus.CONFLICT, e.status());

        // 版本内容仍是首次发布的，生效配置未被篡改
        assertEquals(new BigDecimal("100000"),
                policyStore.find("v2").orElseThrow().config().highAmountThreshold());
        assertEquals(new BigDecimal("100000"), ruleEngine.currentConfig().highAmountThreshold());
        assertEquals(2, policyService.list().size()); // v1(初始) + v2
        // 篡改尝试已留痕
        assertTrue(auditLog.events().stream().anyMatch(ev ->
                ev.action().equals("POLICY_PUBLISH_REJECTED") && ev.subject().equals("v2")));
        assertTrue(auditLog.verify());
    }

    @Test
    @DisplayName("非 POLICY_ADMIN 发布被拒绝、留痕且无任何副作用")
    void unauthorizedPublishRejectedWithoutSideEffects() {
        int versionsBefore = policyService.list().size();
        int auditBefore = auditLog.events().size();

        ApiException e1 = assertThrows(ApiException.class,
                () -> policyService.publish(publishRequest("ana1", Role.ANALYST, v2Config())));
        assertEquals(HttpStatus.FORBIDDEN, e1.status());
        ApiException e2 = assertThrows(ApiException.class,
                () -> policyService.publish(publishRequest("sup1", Role.SUPERVISOR, v2Config())));
        assertEquals(HttpStatus.FORBIDDEN, e2.status());
        ApiException e3 = assertThrows(ApiException.class,
                () -> policyService.publish(publishRequest("aud1", Role.AUDITOR, v2Config())));
        assertEquals(HttpStatus.FORBIDDEN, e3.status());

        // 无副作用：版本数不变、生效版本不变、实时判定仍按 v1
        assertEquals(versionsBefore, policyService.list().size());
        assertEquals("v1", policyService.active().version());
        IngestResult result = transactionService.ingest(tx("TX-P3", "15000"));
        assertEquals("rules:v1/watchlist:v0", result.assessment().ruleSetVersion());

        // 三次越权尝试全部留痕
        List<AuditEvent> denied = auditLog.events().stream()
                .filter(ev -> ev.action().equals("POLICY_PERMISSION_DENIED")).toList();
        assertEquals(3, denied.size());
        assertTrue(auditLog.events().size() > auditBefore);
        assertTrue(auditLog.verify());
    }

    @Test
    @DisplayName("非法策略配置被拒绝（400）且不产生版本")
    void invalidConfigRejected() {
        // reviewThreshold > blockThreshold
        RuleConfig bad1 = new RuleConfig("v-bad1", new BigDecimal("10000"), 40, 60, 5, 30, 50, 90, 80);
        ApiException e1 = assertThrows(ApiException.class,
                () -> policyService.publish(publishRequest("ops1", Role.POLICY_ADMIN, bad1)));
        assertEquals(HttpStatus.BAD_REQUEST, e1.status());

        // 缺少版本号
        RuleConfig bad2 = new RuleConfig(null, new BigDecimal("10000"), 40, 60, 5, 30, 50, 50, 80);
        ApiException e2 = assertThrows(ApiException.class,
                () -> policyService.publish(publishRequest("ops1", Role.POLICY_ADMIN, bad2)));
        assertEquals(HttpStatus.BAD_REQUEST, e2.status());

        // 负分值
        RuleConfig bad3 = new RuleConfig("v-bad3", new BigDecimal("10000"), -1, 60, 5, 30, 50, 50, 80);
        ApiException e3 = assertThrows(ApiException.class,
                () -> policyService.publish(publishRequest("ops1", Role.POLICY_ADMIN, bad3)));
        assertEquals(HttpStatus.BAD_REQUEST, e3.status());

        assertEquals(1, policyService.list().size()); // 仍只有初始 v1
        assertEquals("v1", policyService.active().version());
        assertTrue(auditLog.verify());
    }

    @Test
    @DisplayName("发布与交易并发：同一笔交易完整按某一个版本计算，不新旧混算")
    void concurrentPublishAndIngestConsistentPerTransaction() throws InterruptedException {
        // v2 与 v1 对 8000 元交易的结论完全不同：
        // v1 阈值 10000 -> 不命中(0 分 APPROVE)；v2 阈值 5000、分值 90 -> 命中(90 分 BLOCK)
        RuleConfig v2 = new RuleConfig("v2", new BigDecimal("5000"), 90, 60, 5, 30, 50, 50, 80);

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
        CountDownLatch ready = new CountDownLatch(threads + 1);
        CountDownLatch go = new CountDownLatch(1);
        ConcurrentLinkedQueue<IngestResult> results = new ConcurrentLinkedQueue<>();
        AtomicInteger published = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            // 每笔交易用独立账户，避免并发交易互相触发频率规则干扰断言
            String txId = "TX-C" + i;
            String accountId = "ACC-C" + i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    results.add(transactionService.ingest(tx(txId, accountId, "8000")));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        pool.submit(() -> {
            ready.countDown();
            try {
                go.await();
                policyService.publish(publishRequest("ops1", Role.POLICY_ADMIN, v2));
                published.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(1, published.get());
        assertEquals(threads, results.size());
        for (IngestResult r : results) {
            String tag = r.assessment().ruleSetVersion();
            if (tag.equals("rules:v1/watchlist:v0")) {
                // 完整按 v1：不命中、0 分、APPROVE
                assertEquals(0, r.assessment().totalScore());
                assertEquals(Decision.APPROVE, r.assessment().decision());
                assertTrue(r.assessment().hits().isEmpty());
            } else if (tag.equals("rules:v2/watchlist:v0")) {
                // 完整按 v2：命中 AMOUNT_HIGH、90 分、BLOCK
                assertEquals(90, r.assessment().totalScore());
                assertEquals(Decision.BLOCK, r.assessment().decision());
                assertEquals(1, r.assessment().hits().size());
                assertEquals("AMOUNT_HIGH", r.assessment().hits().get(0).ruleId());
            } else {
                fail("出现版本标签之外的结论: " + tag);
            }
        }
        // 发布完成后的新交易必定按 v2
        IngestResult after = transactionService.ingest(tx("TX-C-AFTER", "ACC-C-AFTER", "8000"));
        assertEquals("rules:v2/watchlist:v0", after.assessment().ruleSetVersion());
        assertEquals(Decision.BLOCK, after.assessment().decision());
        assertTrue(auditLog.verify());
    }
}
