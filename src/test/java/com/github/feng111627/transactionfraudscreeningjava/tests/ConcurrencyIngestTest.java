package com.github.feng111627.transactionfraudscreeningjava.tests;

import com.github.feng111627.transactionfraudscreeningjava.dto.IngestResult;
import com.github.feng111627.transactionfraudscreeningjava.dto.TransactionRequest;
import com.github.feng111627.transactionfraudscreeningjava.rules.AmountRule;
import com.github.feng111627.transactionfraudscreeningjava.rules.RiskRuleEngine;
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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("并发判定：同账户并发交易不漏算不重复计算")
@ExtendWith(CaseResultLogger.class)
class ConcurrencyIngestTest {

    private TransactionStore transactionStore;
    private TransactionService service;

    @BeforeEach
    void setUp() {
        transactionStore = new TransactionStore();
        service = new TransactionService(transactionStore, new WatchlistStore(),
                new ReviewStore(), new AuditLog(),
                new RiskRuleEngine(List.of(new AmountRule(), new VelocityRule(), new WatchlistRule())));
    }

    // 同一账户使用统一事件时间，使窗口计数只取决于入账先后，验证并发下计数精确性
    private static final String FIXED_TIME = Instant.now().toString();

    private TransactionRequest request(String txId, String accountId) {
        return new TransactionRequest(txId, accountId, new BigDecimal("100"),
                "CNY", "M-1", "APP", FIXED_TIME);
    }

    private List<IngestResult> runConcurrently(List<TransactionRequest> requests) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(requests.size());
        CountDownLatch ready = new CountDownLatch(requests.size());
        CountDownLatch go = new CountDownLatch(1);
        ConcurrentLinkedQueue<IngestResult> results = new ConcurrentLinkedQueue<>();
        for (TransactionRequest req : requests) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    results.add(service.ingest(req));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        return List.copyOf(results);
    }

    @Test
    @DisplayName("同账户 20 笔并发：全部入账且频率计数恰好前 5 笔不命中、之后全部命中")
    void concurrentSameAccountVelocity() throws InterruptedException {
        // 默认配置：60 秒窗口内已有 >=5 笔则命中 VELOCITY_HIGH
        List<TransactionRequest> requests = IntStream.rangeClosed(1, 20)
                .mapToObj(i -> request("TX-" + i, "ACC-1"))
                .toList();
        List<IngestResult> results = runConcurrently(requests);

        assertEquals(20, results.size());
        assertEquals(20, results.stream().filter(r -> r.status() == IngestResult.IngestStatus.ACCEPTED).count());
        assertEquals(20, transactionStore.findAll().size());

        long velocityHits = results.stream()
                .filter(r -> r.assessment().hits().stream().anyMatch(h -> h.ruleId().equals("VELOCITY_HIGH")))
                .count();
        // 账户级串行保证：恰好前 5 笔（窗口内计数 0..4）不命中，其余 15 笔全部命中，不漏算不重复
        assertEquals(15, velocityHits);
    }

    @Test
    @DisplayName("同一交易 10 线程并发提交：仅 1 笔入账，其余幂等返回首次结论")
    void concurrentDuplicateSubmission() throws InterruptedException {
        List<TransactionRequest> requests = IntStream.rangeClosed(1, 10)
                .mapToObj(i -> request("TX-DUP", "ACC-2"))
                .toList();
        List<IngestResult> results = runConcurrently(requests);

        assertEquals(1, results.stream().filter(r -> r.status() == IngestResult.IngestStatus.ACCEPTED).count());
        assertEquals(9, results.stream().filter(r -> r.status() == IngestResult.IngestStatus.DUPLICATE).count());
        assertEquals(1, transactionStore.findAll().size());
        // 所有结果返回的是同一个结论对象
        var first = results.get(0).assessment();
        assertTrue(results.stream().allMatch(r -> r.assessment() == first));
    }

    @Test
    @DisplayName("不同账户并发互不干扰：各自独立计数")
    void concurrentDifferentAccounts() throws InterruptedException {
        List<TransactionRequest> requests = IntStream.rangeClosed(1, 30)
                .mapToObj(i -> request("TX-A" + i, "ACC-" + (i % 3)))
                .toList();
        List<IngestResult> results = runConcurrently(requests);

        assertEquals(30, transactionStore.findAll().size());
        // 每账户 10 笔：前 5 笔不命中，后 5 笔命中 -> 共 3 * 5 = 15 次命中
        long velocityHits = results.stream()
                .filter(r -> r.assessment().hits().stream().anyMatch(h -> h.ruleId().equals("VELOCITY_HIGH")))
                .count();
        assertEquals(15, velocityHits);
    }
}
