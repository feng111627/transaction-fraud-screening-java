package com.github.feng111627.transactionfraudscreeningjava.tests;

import com.github.feng111627.transactionfraudscreeningjava.ledger.Ledger;
import com.github.feng111627.transactionfraudscreeningjava.model.Disposition;
import java.util.EnumSet;
import com.github.feng111627.transactionfraudscreeningjava.model.Operator;
import com.github.feng111627.transactionfraudscreeningjava.model.RiskEvaluation;
import com.github.feng111627.transactionfraudscreeningjava.model.Role;
import com.github.feng111627.transactionfraudscreeningjava.model.RuleCode;
import com.github.feng111627.transactionfraudscreeningjava.model.TxnRecord;
import com.github.feng111627.transactionfraudscreeningjava.service.AdminService;
import com.github.feng111627.transactionfraudscreeningjava.service.TransactionService;
import com.github.feng111627.transactionfraudscreeningjava.web.dto.Dtos.RuleConfigRequest;
import com.github.feng111627.transactionfraudscreeningjava.web.dto.Dtos.SubmitTxnRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖业务：风险判定的可解释性与历史结论可复现。
 *  1) 命中规则明细（编号/加分/原因）随结论返回；
 *  2) 更新阈值到新版本后，历史交易复现仍给出当初的分数/处置（绑定 configVersion）。
 */
@SpringBootTest
class RiskAndReproduceTest extends BusinessTest {

    @Autowired
    private TransactionService transactionService;
    @Autowired
    private AdminService adminService;
    @Autowired
    private Ledger ledger;

    private final Operator admin = new Operator("admin", EnumSet.of(Role.ADMIN));
    private final Operator senior = new Operator("senior", EnumSet.of(Role.SENIOR_ANALYST));

    private SubmitTxnRequest txn(String rid, String account, String payee, String amount) {
        return new SubmitTxnRequest(rid, account, payee,
                new BigDecimal(amount), "CNY", System.currentTimeMillis(), "TEST");
    }

    @Test
    void evaluation_explains_each_hit_rule_with_score_and_reason() {
        log.info("覆盖业务[可解释判定] 用例: 大额交易命中 R001+R005，结论中应带每条规则的编号、加分、原因与细节");
        TxnRecord t = transactionService.submitSingle(
                txn("explain-" + UUID.randomUUID(), "AC-EX", "PAYEE-EX", "60000"),
                "TEST", "tester");

        RiskEvaluation ev = t.getEvaluation();
        List<RuleCode> codes = ev.hits().stream().map(h -> h.rule()).toList();
        assertTrue(codes.contains(RuleCode.R001_LARGE_AMOUNT), "应命中大额规则");
        assertTrue(codes.contains(RuleCode.R005_NEW_PAYEE_FIRST_TIME), "应命中新收款方规则");
        int sum = ev.hits().stream().mapToInt(h -> h.score()).sum();
        assertEquals(sum, ev.totalScore(), "总分必须等于各命中规则加分之和");
        assertEquals(45, ev.totalScore(), "30(R001)+15(R005)=45");
        assertEquals(Disposition.REVIEW, ev.disposition(), "45 落在 [40,80) 应转复核");
        ev.hits().forEach(h -> {
            assertNotNull(h.reason());
            assertNotNull(h.detail());
            assertTrue(h.score() > 0);
        });
        assertEquals(1, ev.configVersion(), "必须记录判定时的配置版本");
    }

    @Test
    void historical_conclusion_reproduces_after_threshold_change() {
        log.info("覆盖业务[历史结论可复现] 用例: 用 v1 判定大额后发布更严的 v2 阈值，复现该历史交易仍得到 v1 结论");
        TxnRecord historical = transactionService.submitSingle(
                txn("repro-" + UUID.randomUUID(), "AC-REPRO", "PAYEE-REPRO", "60000"),
                "TEST", "tester");
        int originalScore = historical.getEvaluation().totalScore();
        Disposition originalDisposition = historical.getEvaluation().disposition();
        long originalVersion = historical.getEvaluation().configVersion();
        assertEquals(1L, originalVersion);

        // 发布 v2：大额阈值降到 30000，分值提高到 50；复核阈值降到 20
        RuleConfigRequest v2 = new RuleConfigRequest(
                new BigDecimal("30000"), 50, null, null, null,
                null, null, null, 20, null);
        assertEquals(2L, adminService.updateRuleConfig(v2, admin).version());

        // 新交易受 v2 影响（60000 在 v2 下大额加 50）
        TxnRecord fresh = transactionService.submitSingle(
                txn("fresh-" + UUID.randomUUID(), "AC-FRESH", "PAYEE-FRESH", "60000"),
                "TEST", "tester");
        assertEquals(2L, fresh.getEvaluation().configVersion());

        // 历史交易按 v1 复现：分数/处置/命中规则数与当初一致
        RiskEvaluation reproduced = adminService.reproduce(historical.getTxnId(), senior);
        assertEquals(originalVersion, reproduced.configVersion());
        assertEquals(originalScore, reproduced.totalScore(),
                "复现必须使用历史配置版本，分数应与当初一致");
        assertEquals(originalDisposition, reproduced.disposition());
        assertEquals(historical.getEvaluation().hits().size(), reproduced.hits().size());

        log.info("覆盖业务[历史结论可复现] 结果: 历史={} 分/{}(v{}), 复现={} 分/{}(v{})",
                originalScore, originalDisposition, originalVersion,
                reproduced.totalScore(), reproduced.disposition(), reproduced.configVersion());
    }
}
