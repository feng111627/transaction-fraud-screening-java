package com.github.feng111627.transactionfraudscreeningjava.tests;

import com.github.feng111627.transactionfraudscreeningjava.error.AccessDeniedException;
import com.github.feng111627.transactionfraudscreeningjava.error.ConflictException;
import com.github.feng111627.transactionfraudscreeningjava.ledger.Ledger;
import com.github.feng111627.transactionfraudscreeningjava.model.AuditAction;
import com.github.feng111627.transactionfraudscreeningjava.model.Disposition;
import com.github.feng111627.transactionfraudscreeningjava.model.Operator;
import com.github.feng111627.transactionfraudscreeningjava.model.ReviewRecord;
import com.github.feng111627.transactionfraudscreeningjava.model.ReviewStatus;
import com.github.feng111627.transactionfraudscreeningjava.model.Role;
import com.github.feng111627.transactionfraudscreeningjava.model.TxnRecord;
import com.github.feng111627.transactionfraudscreeningjava.service.ReviewService;
import com.github.feng111627.transactionfraudscreeningjava.service.TransactionService;
import com.github.feng111627.transactionfraudscreeningjava.web.dto.Dtos.SubmitTxnRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖业务：人工复核状态机与权限。
 * 用例：
 *  1) PENDING 直接结案 -> ILLEGAL_TRANSITION 拒绝；
 *  2) RESOLVED 再认领 -> ILLEGAL_TRANSITION 拒绝；
 *  3) 普通 ANALYST 覆盖系统判断 -> 403 拒绝并留 ACCESS_DENIED 审计；
 *  4) 非认领人（哪怕是高级）结案 -> 403；
 *  5) SENIOR_ANALYST 认领并覆盖结案 -> 成功且标记 overridden。
 */
@SpringBootTest
class ReviewFlowTest extends BusinessTest {

    @Autowired
    private TransactionService transactionService;
    @Autowired
    private ReviewService reviewService;
    @Autowired
    private Ledger ledger;

    private final Operator analyst = new Operator("analyst-a", EnumSet.of(Role.ANALYST));
    private final Operator senior = new Operator("senior-b", EnumSet.of(Role.SENIOR_ANALYST));

    /** 造一笔系统处置为 REVIEW 的交易（大额 60000：R001=30 + R005=15 = 45，落 REVIEW） */
    private String createReviewTxn() {
        String uuid = UUID.randomUUID().toString();
        SubmitTxnRequest req = new SubmitTxnRequest(
                "rev-" + uuid, "AC-" + uuid, "PAYEE-" + uuid,
                new BigDecimal("60000"), "CNY", System.currentTimeMillis(), "TEST");
        TxnRecord txn = transactionService.submitSingle(req, "TEST", "setup");
        assertEquals(Disposition.REVIEW, txn.getEvaluation().disposition());
        return ledger.findReviewByTxnId(txn.getTxnId()).orElseThrow().getReviewId();
    }

    @Test
    void resolve_pending_review_is_illegal_transition() {
        log.info("覆盖业务[非法状态流转] 用例: PENDING 未认领直接结案，期望 ILLEGAL_TRANSITION");
        String reviewId = createReviewTxn();
        ConflictException ex = assertThrows(ConflictException.class,
                () -> reviewService.resolve(reviewId, Disposition.APPROVE, "直接结案", analyst));
        assertEquals("ILLEGAL_TRANSITION", ex.getCode());
        assertEquals(ReviewStatus.PENDING, reviewService.get(reviewId).getStatus());
    }

    @Test
    void claim_after_resolved_is_illegal_transition() {
        log.info("覆盖业务[非法状态流转] 用例: 已 RESOLVED 的复核单再次认领，期望 ILLEGAL_TRANSITION");
        String reviewId = createReviewTxn();
        reviewService.claim(reviewId, senior);
        reviewService.resolve(reviewId, Disposition.APPROVE, "维持系统判断", senior);

        ConflictException ex = assertThrows(ConflictException.class,
                () -> reviewService.claim(reviewId, analyst));
        assertEquals("ILLEGAL_TRANSITION", ex.getCode());
        assertEquals(ReviewStatus.RESOLVED, reviewService.get(reviewId).getStatus());
    }

    @Test
    void analyst_cannot_override_system_decision_and_it_is_audited() {
        log.info("覆盖业务[越权] 用例: 普通 ANALYST 试图把系统 REVIEW 覆盖为 APPROVE，期望 403 且审计留痕");
        String reviewId = createReviewTxn();
        reviewService.claim(reviewId, analyst);

        AccessDeniedException ex = assertThrows(AccessDeniedException.class,
                () -> reviewService.resolve(reviewId, Disposition.APPROVE, "我认为没问题", analyst));
        assertEquals("ACCESS_DENIED", ex.getCode());

        ReviewRecord unchanged = reviewService.get(reviewId);
        assertEquals(ReviewStatus.INVESTIGATING, unchanged.getStatus(), "越权后状态不能被改动");

        boolean hasDenyAudit = ledger.allAudits().stream()
                .anyMatch(a -> a.action() == AuditAction.ACCESS_DENIED
                        && reviewId.equals(a.targetRef())
                        && "analyst-a".equals(a.operator())
                        && "DENIED".equals(a.result()));
        assertTrue(hasDenyAudit, "越权操作必须写入不可篡改的审计轨迹");
    }

    @Test
    void non_claimer_cannot_resolve_even_with_senior_role() {
        log.info("覆盖业务[越权] 用例: 非认领人（高级分析师）尝试结案，期望 403");
        String reviewId = createReviewTxn();
        reviewService.claim(reviewId, analyst);

        assertThrows(AccessDeniedException.class,
                () -> reviewService.resolve(reviewId, Disposition.APPROVE, "抢单结案", senior));
        assertEquals(ReviewStatus.INVESTIGATING, reviewService.get(reviewId).getStatus());
    }

    @Test
    void senior_claimer_can_override_and_resolve() {
        log.info("覆盖业务[合法覆盖] 用例: 认领人是 SENIOR_ANALYST，可覆盖系统判断结案，overridden=true");
        String reviewId = createReviewTxn();
        reviewService.claim(reviewId, senior);
        ReviewRecord resolved = reviewService.resolve(
                reviewId, Disposition.APPROVE, "高级复核确认放行", senior);

        assertEquals(ReviewStatus.RESOLVED, resolved.getStatus());
        assertEquals(Disposition.APPROVE, resolved.getFinalDisposition());
        assertTrue(resolved.isOverridden(), "最终结论与系统 REVIEW 不同，必须标记 overridden");
    }

    @Test
    void analyst_can_confirm_system_decision_without_override() {
        log.info("覆盖业务[普通结案] 用例: ANALYST 认领后可维持系统建议 REJECT 结案（不构成覆盖，无需高级权限）");
        // 直接开一张系统建议为 REJECT 的复核单（例如系统判定拒绝后人工复核场景）
        ReviewRecord opened = ReviewRecord.open(
                ledger.nextReviewId(), "TXN-LEGACY-" + UUID.randomUUID(),
                Disposition.REJECT, System.currentTimeMillis());
        ledger.saveReview(opened);

        reviewService.claim(opened.getReviewId(), analyst);
        ReviewRecord resolved = reviewService.resolve(
                opened.getReviewId(), Disposition.REJECT, "维持系统拒绝建议", analyst);
        assertEquals(ReviewStatus.RESOLVED, resolved.getStatus());
        assertFalse(resolved.isOverridden());
    }
}
