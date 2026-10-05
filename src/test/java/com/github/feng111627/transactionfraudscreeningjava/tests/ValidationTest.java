package com.github.feng111627.transactionfraudscreeningjava.tests;

import com.github.feng111627.transactionfraudscreeningjava.error.ValidationException;
import com.github.feng111627.transactionfraudscreeningjava.ledger.Ledger;
import com.github.feng111627.transactionfraudscreeningjava.model.TxnRecord;
import com.github.feng111627.transactionfraudscreeningjava.model.TxnStatus;
import com.github.feng111627.transactionfraudscreeningjava.service.TransactionService;
import com.github.feng111627.transactionfraudscreeningjava.web.dto.Dtos.SubmitTxnRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 覆盖业务：交易字段校验（非法/缺失按不同原因明确拒绝，不静默丢弃）与批量提交。
 */
@SpringBootTest
class ValidationTest extends BusinessTest {

    @Autowired
    private TransactionService transactionService;
    @Autowired
    private Ledger ledger;

    private SubmitTxnRequest base(String rid) {
        return new SubmitTxnRequest(rid, "AC-V", "PAYEE-V",
                new BigDecimal("100"), "CNY", System.currentTimeMillis(), "TEST");
    }

    private void expectReject(SubmitTxnRequest req, String code) {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> transactionService.submitSingle(req, "TEST", "tester"));
        assertEquals(code, ex.getCode(), "拒绝原因必须明确为 " + code);
    }

    @Test
    void blank_request_id_is_rejected_with_reason() {
        log.info("覆盖业务[字段校验] 用例: requestId 为空 -> REQUEST_ID_BLANK");
        expectReject(base("  "), "REQUEST_ID_BLANK");
    }

    @Test
    void blank_account_is_rejected_with_reason() {
        log.info("覆盖业务[字段校验] 用例: accountId 为空 -> ACCOUNT_ID_BLANK");
        SubmitTxnRequest r = base(uuid());
        expectReject(new SubmitTxnRequest(r.requestId(), " ", r.targetId(),
                r.amount(), r.currency(), r.txnTimeEpochMilli(), r.source()), "ACCOUNT_ID_BLANK");
    }

    @Test
    void blank_target_is_rejected_with_reason() {
        log.info("覆盖业务[字段校验] 用例: targetId 为空 -> TARGET_ID_BLANK");
        SubmitTxnRequest r = base(uuid());
        expectReject(new SubmitTxnRequest(r.requestId(), r.accountId(), null,
                r.amount(), r.currency(), r.txnTimeEpochMilli(), r.source()), "TARGET_ID_BLANK");
    }

    @Test
    void null_or_non_positive_amount_is_rejected_with_reason() {
        log.info("覆盖业务[字段校验] 用例: 金额为空/非正数 -> AMOUNT_NULL / AMOUNT_NOT_POSITIVE");
        SubmitTxnRequest r = base(uuid());
        expectReject(new SubmitTxnRequest(r.requestId(), r.accountId(), r.targetId(),
                null, r.currency(), r.txnTimeEpochMilli(), r.source()), "AMOUNT_NULL");
        SubmitTxnRequest r2 = base(uuid());
        expectReject(new SubmitTxnRequest(r2.requestId(), r2.accountId(), r2.targetId(),
                BigDecimal.ZERO, r2.currency(), r2.txnTimeEpochMilli(), r2.source()),
                "AMOUNT_NOT_POSITIVE");
    }

    @Test
    void blank_currency_and_bad_time_are_rejected_with_reasons() {
        log.info("覆盖业务[字段校验] 用例: 币种为空 / 时间为空 / 未来时间 / 过旧时间");
        SubmitTxnRequest r = base(uuid());
        expectReject(new SubmitTxnRequest(r.requestId(), r.accountId(), r.targetId(),
                r.amount(), "", r.txnTimeEpochMilli(), r.source()), "CURRENCY_BLANK");
        expectReject(new SubmitTxnRequest(uuid(), r.accountId(), r.targetId(),
                r.amount(), r.currency(), null, r.source()), "TXN_TIME_NULL");
        expectReject(new SubmitTxnRequest(uuid(), r.accountId(), r.targetId(),
                r.amount(), r.currency(), System.currentTimeMillis() + 3_600_000, r.source()),
                "TXN_TIME_IN_FUTURE");
        expectReject(new SubmitTxnRequest(uuid(), r.accountId(), r.targetId(),
                r.amount(), r.currency(), System.currentTimeMillis() - 31L * 24 * 3600_000,
                r.source()), "TXN_TIME_TOO_OLD");
    }

    @Test
    void batch_invalid_items_are_rejected_but_valid_ones_get_conclusions() {
        log.info("覆盖业务[批量提交] 用例: 3 笔中 1 笔非法，合法笔正常出结论、非法笔带拒绝原因且不影响其他笔");
        int before = ledger.allTxns().size();
        SubmitTxnRequest ok1 = base(uuid());
        SubmitTxnRequest bad = new SubmitTxnRequest(uuid(), "AC-V", "PAYEE-V",
                new BigDecimal("-5"), "CNY", System.currentTimeMillis(), "TEST");
        SubmitTxnRequest ok2 = base(uuid());

        List<TxnRecord> results = transactionService.submitBatch(List.of(ok1, bad, ok2), "tester");

        assertEquals(3, results.size());
        assertEquals(TxnStatus.ACCEPTED, results.get(0).getStatus());
        assertEquals(TxnStatus.REJECTED, results.get(1).getStatus());
        assertEquals(com.github.feng111627.transactionfraudscreeningjava.model.RejectReason.AMOUNT_NOT_POSITIVE,
                results.get(1).getRejectReason());
        assertEquals(TxnStatus.ACCEPTED, results.get(2).getStatus());
        assertEquals(before + 3, ledger.allTxns().size(), "被拒绝的交易也要留痕（不静默丢弃）");
    }

    private String uuid() {
        return UUID.randomUUID().toString();
    }
}
