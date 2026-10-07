package com.github.feng111627.transactionfraudscreeningjava.tests;

import com.github.feng111627.transactionfraudscreeningjava.domain.Decision;
import com.github.feng111627.transactionfraudscreeningjava.domain.PolicyVersion;
import com.github.feng111627.transactionfraudscreeningjava.domain.Role;
import com.github.feng111627.transactionfraudscreeningjava.dto.IngestResult;
import com.github.feng111627.transactionfraudscreeningjava.dto.PolicySpecRequest;
import com.github.feng111627.transactionfraudscreeningjava.dto.TransactionRequest;
import com.github.feng111627.transactionfraudscreeningjava.exception.ApiException;
import com.github.feng111627.transactionfraudscreeningjava.rules.AmountRule;
import com.github.feng111627.transactionfraudscreeningjava.rules.RiskRuleEngine;
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
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("策略发布：版本不可变 / 生效切换 / 权限与留痕")
@ExtendWith(CaseResultLogger.class)
class PolicyPublishTest {

    private PolicyStore policyStore;
    private AuditLog auditLog;
    private PolicyService policyService;
    private TransactionService transactionService;
    private TransactionStore transactionStore;

    @BeforeEach
    void setUp() {
        policyStore = new PolicyStore();
        auditLog = new AuditLog();
        RiskRuleEngine ruleEngine = new RiskRuleEngine(
                List.of(new AmountRule(), new VelocityRule(), new WatchlistRule()));
        policyService = new PolicyService(policyStore, ruleEngine, auditLog);
        transactionStore = new TransactionStore();
        transactionService = new TransactionService(transactionStore, new WatchlistStore(),
                new ReviewStore(), auditLog, ruleEngine);
    }

    private PolicySpecRequest spec(String version, Role role, String highAmountThreshold) {
        return new PolicySpecRequest("ops1", role, version,
                new BigDecimal(highAmountThreshold), 40, 60, 5, 30, 50, 50, 80);
    }

    private TransactionRequest tx(String id, String amount) {
        return new TransactionRequest(id, "A1", new BigDecimal(amount),
                "CNY", "M-1", "APP", Instant.now().toString());
    }

    private TransactionRequest txForAccount(String id, String accountId, String amount) {
        return new TransactionRequest(id, accountId, new BigDecimal(amount),
                "CNY", "M-1", "APP", Instant.now().toString());
    }

    @Test
    @DisplayName("发布新版本后新交易按新版本判定，历史结论不被改写")
    void publishSwitchesNewTransactionsOnly() {
        IngestResult before = transactionService.ingest(tx("P-T1", "15000"));
        assertEquals(40, before.assessment().totalScore());
        assertEquals("rules:v1/watchlist:v0", before.assessment().ruleSetVersion());

        PolicyVersion v2 = policyService.publish(spec("v2", Role.POLICY_ADMIN, "100000"));
        assertEquals("ops1", v2.publishedBy());
        assertNotNull(v2.publishedAt());
        assertEquals("v2", policyStore.active().version());

        // 历史结论保持 v1 口径
        assertEquals("rules:v1/watchlist:v0",
                transactionStore.findById("P-T1").orElseThrow().assessment().ruleSetVersion());
        // 新交易按 v2：15000 不再命中大额规则
        IngestResult after = transactionService.ingest(tx("P-T2", "15000"));
        assertEquals(0, after.assessment().totalScore());
        assertEquals(Decision.APPROVE, after.assessment().decision());
        assertEquals("v2", after.assessment().policyVersion());
        // 发布动作留痕
        assertTrue(auditLog.events().stream().anyMatch(e -> e.action().equals("POLICY_PUBLISHED")
                && e.subject().equals("policy:v2") && e.actor().equals("ops1")));
        assertTrue(auditLog.verify());
    }

    @Test
    @DisplayName("已发布版本不可变：同版本号重复发布被拒绝且无副作用")
    void duplicateVersionRejected() {
        policyService.publish(spec("v2", Role.POLICY_ADMIN, "100000"));
        int versionsBefore = policyStore.all().size();
        int auditBefore = auditLog.events().size();

        ApiException ex = assertThrows(ApiException.class,
                () -> policyService.publish(spec("v2", Role.POLICY_ADMIN, "500")));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        // 无副作用：版本表与生效版本不变，原 v2 内容不变
        assertEquals(versionsBefore, policyStore.all().size());
        assertEquals(new BigDecimal("100000"),
                policyStore.find("v2").orElseThrow().config().highAmountThreshold());
        assertEquals("v2", policyStore.active().version());
        // 拒绝留痕
        assertTrue(auditLog.events().size() > auditBefore);
        assertTrue(auditLog.events().stream().anyMatch(e -> e.action().equals("POLICY_PUBLISH_REJECTED")));
        assertTrue(auditLog.verify());
    }

    @Test
    @DisplayName("非 POLICY_ADMIN 发布被拒绝（403）且无副作用、有留痕")
    void nonAdminPublishForbidden() {
        int versionsBefore = policyStore.all().size();

        ApiException ex = assertThrows(ApiException.class,
                () -> policyService.publish(spec("v9", Role.ANALYST, "100")));
        assertEquals(HttpStatus.FORBIDDEN, ex.status());
        assertEquals(versionsBefore, policyStore.all().size());
        assertEquals("v1", policyStore.active().version());
        assertTrue(auditLog.events().stream().anyMatch(e -> e.action().equals("PERMISSION_DENIED")
                && e.subject().equals("policy:v9")));
        // 实时判定仍按 v1
        IngestResult r = transactionService.ingest(tx("P-T3", "15000"));
        assertEquals(40, r.assessment().totalScore());
        assertTrue(auditLog.verify());
    }

    @Test
    @DisplayName("策略内容非法（阈值倒挂/缺字段）被拒绝（400）且无副作用")
    void invalidSpecRejected() {
        // reviewThreshold > blockThreshold
        PolicySpecRequest inverted = new PolicySpecRequest("ops1", Role.POLICY_ADMIN, "v3",
                new BigDecimal("100"), 40, 60, 5, 30, 50, 90, 50);
        ApiException ex = assertThrows(ApiException.class, () -> policyService.publish(inverted));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertTrue(ex.getMessage().contains("reviewThreshold"));

        // 缺字段
        PolicySpecRequest missing = new PolicySpecRequest("ops1", Role.POLICY_ADMIN, "v3",
                null, 40, 60, 5, 30, 50, 50, 80);
        ApiException ex2 = assertThrows(ApiException.class, () -> policyService.publish(missing));
        assertEquals(HttpStatus.BAD_REQUEST, ex2.status());
        assertTrue(ex2.getMessage().contains("highAmountThreshold"));

        assertEquals(1, policyStore.all().size());
        assertEquals("v1", policyStore.active().version());
    }

    @Test
    @DisplayName("缺少 operator/role 的发布请求被拒绝（400）")
    void missingOperatorRejected() {
        PolicySpecRequest noOperator = new PolicySpecRequest(null, Role.POLICY_ADMIN, "v4",
                new BigDecimal("100"), 40, 60, 5, 30, 50, 50, 80);
        ApiException ex = assertThrows(ApiException.class, () -> policyService.publish(noOperator));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());

        PolicySpecRequest noRole = new PolicySpecRequest("ops1", null, "v4",
                new BigDecimal("100"), 40, 60, 5, 30, 50, 50, 80);
        assertThrows(ApiException.class, () -> policyService.publish(noRole));
        assertEquals(1, policyStore.all().size());
    }

    @Test
    @DisplayName("发布与并发交易同时发生：每笔交易完整按某一个版本判定，无新旧混合")
    void concurrentPublishAndIngest() throws InterruptedException {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ConcurrentLinkedQueue<IngestResult> results = new ConcurrentLinkedQueue<>();
        for (int i = 1; i <= threads - 1; i++) {
            int id = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    results.add(transactionService.ingest(txForAccount("P-C" + id, "ACC-" + id, "15000")));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        pool.submit(() -> {
            ready.countDown();
            try {
                go.await();
                policyService.publish(spec("v2", Role.POLICY_ADMIN, "100000"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(threads - 1, results.size());
        for (IngestResult r : results) {
            String version = r.assessment().policyVersion();
            // 每笔结论必须与其版本自洽：v1 下 15000 命中大额规则得 40 分，v2 下不命中得 0 分
            if (version.equals("v1")) {
                assertEquals(40, r.assessment().totalScore());
                assertEquals("rules:v1/watchlist:v0", r.assessment().ruleSetVersion());
            } else {
                assertEquals("v2", version);
                assertEquals(0, r.assessment().totalScore());
                assertEquals("rules:v2/watchlist:v0", r.assessment().ruleSetVersion());
            }
        }
        assertTrue(auditLog.verify());
    }
}
