package com.github.feng111627.transactionfraudscreeningjava.tests;

import com.github.feng111627.transactionfraudscreeningjava.error.ConflictException;
import com.github.feng111627.transactionfraudscreeningjava.ledger.Ledger;
import com.github.feng111627.transactionfraudscreeningjava.model.TxnRecord;
import com.github.feng111627.transactionfraudscreeningjava.service.TransactionService;
import com.github.feng111627.transactionfraudscreeningjava.web.dto.Dtos.SubmitTxnRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 覆盖业务：交易幂等。
 * 用例：1) 同 requestId 重复提交不重复落账/不重复出结论；
 *      2) 重复提交返回同一结论；
 *      3) 相同 requestId 但内容不一致被拒绝（IDEMPOTENCY_MISMATCH）。
 */
@SpringBootTest
class IdempotencyTest extends BusinessTest {

    @Autowired
    private TransactionService transactionService;
    @Autowired
    private Ledger ledger;

    private final AtomicLong seq = new AtomicLong();

    private SubmitTxnRequest req(String requestId, String account, String target, String amount) {
        return new SubmitTxnRequest(requestId, account, target,
                new BigDecimal(amount), "CNY", System.currentTimeMillis(), "TEST");
    }

    @Test
    void duplicate_request_id_does_not_double_count_or_reevaluate() {
        log.info("覆盖业务[交易幂等] 用例: 同一 requestId 重复提交两次，期望只落账一笔、只出一次结论");
        String requestId = "idem-1-" + seq.incrementAndGet();
        int before = ledger.allTxns().size();

        SubmitTxnRequest sameReq = req(requestId, "AC-IDEM-1", "PAYEE-1", "100");
        TxnRecord first = transactionService.submitSingle(sameReq, "TEST", "tester");
        TxnRecord second = transactionService.submitSingle(sameReq, "TEST", "tester");

        assertEquals(first.getTxnId(), second.getTxnId());
        assertSame(first, second, "重复提交必须返回首次的同一条记录");
        assertNotNull(first.getEvaluation());
        assertEquals(before + 1, ledger.allTxns().size(), "重复提交不能新增账本记录");
        assertEquals(1, ledger.allTxns().stream()
                .filter(t -> requestId.equals(t.getRequestId())).count());
    }

    @Test
    void duplicate_request_returns_same_conclusion() {
        log.info("覆盖业务[交易幂等] 用例: 重复提交返回相同的风险分与处置结论");
        String requestId = "idem-2-" + seq.incrementAndGet();
        SubmitTxnRequest sameReq = req(requestId, "AC-IDEM-2", "PAYEE-2", "100");
        TxnRecord first = transactionService.submitSingle(sameReq, "TEST", "tester");
        TxnRecord second = transactionService.submitSingle(sameReq, "TEST", "tester");

        assertEquals(first.getEvaluation().totalScore(), second.getEvaluation().totalScore());
        assertEquals(first.getEvaluation().disposition(), second.getEvaluation().disposition());
    }

    @Test
    void same_request_id_with_different_payload_is_rejected() {
        log.info("覆盖业务[交易幂等] 用例: 相同 requestId 但金额不一致，期望 409 IDEMPOTENCY_MISMATCH");
        String requestId = "idem-3-" + seq.incrementAndGet();
        transactionService.submitSingle(
                req(requestId, "AC-IDEM-3", "PAYEE-3", "100"), "TEST", "tester");

        SubmitTxnRequest changed = new SubmitTxnRequest(requestId, "AC-IDEM-3", "PAYEE-3",
                new BigDecimal("999"), "CNY", System.currentTimeMillis(), "TEST");
        ConflictException ex = assertThrows(ConflictException.class,
                () -> transactionService.submitSingle(changed, "TEST", "tester"));
        assertEquals("IDEMPOTENCY_MISMATCH", ex.getCode());
    }
}
