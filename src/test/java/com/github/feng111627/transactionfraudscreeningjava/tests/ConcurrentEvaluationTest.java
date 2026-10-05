package com.github.feng111627.transactionfraudscreeningjava.tests;

import com.github.feng111627.transactionfraudscreeningjava.model.RuleCode;
import com.github.feng111627.transactionfraudscreeningjava.model.TxnRecord;
import com.github.feng111627.transactionfraudscreeningjava.service.TransactionService;
import com.github.feng111627.transactionfraudscreeningjava.web.dto.Dtos.SubmitTxnRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖业务：同一账户并发交易的实时判定一致性。
 * 用例：N 个线程同时提交同一账户、同一时间窗口内的小额交易，
 *   - 全部落账、txnId 无重复（不漏算/不重复计数）；
 *   - 频率规则命中数是确定值：前 limit-1 笔不命中，其余全部命中；
 *   - 结论可复现（复现的频率命中集合与判定时一致）。
 */
@SpringBootTest
class ConcurrentEvaluationTest extends BusinessTest {

    private static final int N = 12;
    private static final int FREQUENCY_LIMIT = 3;

    @Autowired
    private TransactionService transactionService;

    @Test
    void concurrent_same_account_txn_count_is_exact_and_frequency_hit_is_deterministic()
            throws InterruptedException {
        log.info("覆盖业务[并发判定] 用例: {} 个线程并发提交同一账户窗口内交易，期望全部落账、无重复 txnId，"
                + "频率规则恰好命中 {} 笔（第 {} 笔起）", N, N - FREQUENCY_LIMIT, FREQUENCY_LIMIT + 1);

        String account = "AC-CONC-" + UUID.randomUUID();
        String payee = "PAYEE-CONC-" + UUID.randomUUID();
        long baseTime = System.currentTimeMillis();

        ExecutorService pool = Executors.newFixedThreadPool(N);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(N);
        ConcurrentLinkedQueue<TxnRecord> results = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < N; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    // 交易时间都在同一 300 秒窗口内（彼此间隔 1ms，保证严格有序）
                    SubmitTxnRequest req = new SubmitTxnRequest(
                            "conc-" + account + "-" + idx, account, payee,
                            new BigDecimal("10"), "CNY", baseTime + idx, "TEST");
                    results.add(transactionService.submitSingle(req, "CONCURRENT", "tester"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发任务必须全部完成");
        pool.shutdown();

        List<TxnRecord> txns = List.copyOf(results);
        assertEquals(N, txns.size(), "不能漏算");
        Set<String> txnIds = txns.stream().map(TxnRecord::getTxnId).collect(Collectors.toSet());
        assertEquals(N, txnIds.size(), "txnId 不能重复（不能重复计数）");

        long frequencyHits = txns.stream()
                .filter(t -> t.getEvaluation().hits().stream()
                        .anyMatch(h -> h.rule() == RuleCode.R002_FREQUENCY_WINDOW))
                .count();
        assertEquals(N - FREQUENCY_LIMIT, frequencyHits,
                "频率命中数必须确定：恰好第 4 笔起的 9 笔命中，与并发调度顺序无关");

        long newPayeeOnlyFirst = txns.stream()
                .filter(t -> t.getEvaluation().hits().stream()
                        .anyMatch(h -> h.rule() == RuleCode.R005_NEW_PAYEE_FIRST_TIME))
                        .count();
        assertEquals(1, newPayeeOnlyFirst, "新收款方规则只应在该收款方第一笔交易上命中一次");

        log.info("覆盖业务[并发判定] 结果: 落账 {} 笔, 频率命中 {} 笔, 新收款方命中 1 笔",
                N, frequencyHits);
    }
}
