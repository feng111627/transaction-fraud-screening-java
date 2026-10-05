package com.github.feng111627.transactionfraudscreeningjava.tests;

import com.github.feng111627.transactionfraudscreeningjava.domain.AuditEvent;
import com.github.feng111627.transactionfraudscreeningjava.domain.Decision;
import com.github.feng111627.transactionfraudscreeningjava.domain.ReviewCase;
import com.github.feng111627.transactionfraudscreeningjava.domain.ReviewStatus;
import com.github.feng111627.transactionfraudscreeningjava.domain.Role;
import com.github.feng111627.transactionfraudscreeningjava.dto.ReviewActionRequest;
import com.github.feng111627.transactionfraudscreeningjava.dto.TransactionRequest;
import com.github.feng111627.transactionfraudscreeningjava.exception.ApiException;
import com.github.feng111627.transactionfraudscreeningjava.rules.AmountRule;
import com.github.feng111627.transactionfraudscreeningjava.rules.RiskRuleEngine;
import com.github.feng111627.transactionfraudscreeningjava.rules.VelocityRule;
import com.github.feng111627.transactionfraudscreeningjava.rules.WatchlistRule;
import com.github.feng111627.transactionfraudscreeningjava.service.ReviewService;
import com.github.feng111627.transactionfraudscreeningjava.service.TransactionService;
import com.github.feng111627.transactionfraudscreeningjava.store.AuditLog;
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

@DisplayName("人工复核：状态机 / 权限 / 审计留痕")
@ExtendWith(CaseResultLogger.class)
class ReviewServiceTest {

    private ReviewStore reviewStore;
    private AuditLog auditLog;
    private ReviewService reviewService;
    private TransactionService transactionService;
    private WatchlistStore watchlistStore;

    @BeforeEach
    void setUp() {
        TransactionStore transactionStore = new TransactionStore();
        reviewStore = new ReviewStore();
        auditLog = new AuditLog();
        watchlistStore = new WatchlistStore();
        reviewService = new ReviewService(reviewStore, transactionStore, auditLog);
        transactionService = new TransactionService(transactionStore, watchlistStore,
                reviewStore, auditLog,
                new RiskRuleEngine(List.of(new AmountRule(), new VelocityRule(), new WatchlistRule())));
    }

    /** 制造一笔命中名单的交易（50 分，系统结论 REVIEW），返回自动开立的工单。 */
    private ReviewCase openCaseFromBigAmount(String txId) {
        watchlistStore.add("ACC-R", "测试名单");
        transactionService.ingest(new TransactionRequest(txId, "ACC-R", new BigDecimal("100"),
                "CNY", "M-1", "APP", Instant.now().toString()));
        return reviewStore.findByTransactionId(txId).orElseThrow();
    }

    private ReviewActionRequest action(String operator, Role role, ReviewStatus target) {
        return new ReviewActionRequest(operator, role, target, "note");
    }

    @Test
    @DisplayName("系统结论非 APPROVE 时自动开立 OPEN 工单")
    void autoOpenCase() {
        ReviewCase c = openCaseFromBigAmount("TX-R1");
        assertEquals(ReviewStatus.OPEN, c.status());
    }

    @Test
    @DisplayName("合法流转 OPEN->IN_PROGRESS->RESOLVED_APPROVE->CLOSED 全部成功")
    void legalTransitionChain() {
        ReviewCase c = openCaseFromBigAmount("TX-R2");
        String id = c.caseId();
        // 系统结论 REVIEW，人工 APPROVE 属于覆盖 -> 需要 SUPERVISOR
        c = reviewService.transition(id, action("sup1", Role.SUPERVISOR, ReviewStatus.IN_PROGRESS));
        assertEquals(ReviewStatus.IN_PROGRESS, c.status());
        c = reviewService.transition(id, action("sup1", Role.SUPERVISOR, ReviewStatus.RESOLVED_APPROVE));
        assertEquals(ReviewStatus.RESOLVED_APPROVE, c.status());
        c = reviewService.transition(id, action("sup2", Role.SUPERVISOR, ReviewStatus.CLOSED));
        assertEquals(ReviewStatus.CLOSED, c.status());
        assertEquals(ReviewStatus.CLOSED, reviewStore.findById(id).orElseThrow().status());
    }

    @Test
    @DisplayName("非法状态迁移被拒绝并留痕：OPEN 不能直接 RESOLVED，CLOSED 是终态")
    void illegalTransitionsRejected() {
        ReviewCase c = openCaseFromBigAmount("TX-R3");
        String id = c.caseId();

        ApiException e1 = assertThrows(ApiException.class,
                () -> reviewService.transition(id, action("ana1", Role.ANALYST, ReviewStatus.RESOLVED_BLOCK)));
        assertEquals(HttpStatus.CONFLICT, e1.status());

        ApiException e2 = assertThrows(ApiException.class,
                () -> reviewService.transition(id, action("ana1", Role.ANALYST, ReviewStatus.CLOSED)));
        assertEquals(HttpStatus.CONFLICT, e2.status());

        // 走到终态后再迁移也拒绝
        reviewService.transition(id, action("sup1", Role.SUPERVISOR, ReviewStatus.IN_PROGRESS));
        reviewService.transition(id, action("sup1", Role.SUPERVISOR, ReviewStatus.RESOLVED_BLOCK));
        reviewService.transition(id, action("sup1", Role.SUPERVISOR, ReviewStatus.CLOSED));
        ApiException e3 = assertThrows(ApiException.class,
                () -> reviewService.transition(id, action("sup1", Role.SUPERVISOR, ReviewStatus.IN_PROGRESS)));
        assertEquals(HttpStatus.CONFLICT, e3.status());

        // 状态未被破坏
        assertEquals(ReviewStatus.CLOSED, reviewStore.findById(id).orElseThrow().status());
        // 非法迁移已留痕
        List<AuditEvent> rejected = auditLog.events().stream()
                .filter(e -> e.action().equals("REVIEW_TRANSITION_REJECTED")).toList();
        assertEquals(3, rejected.size());
        assertTrue(auditLog.verify());
    }

    @Test
    @DisplayName("ANALYST 不能覆盖系统结论，SUPERVISOR 可以")
    void overrideRequiresSupervisor() {
        ReviewCase c = openCaseFromBigAmount("TX-R4"); // 系统 REVIEW
        String id = c.caseId();
        reviewService.transition(id, action("ana1", Role.ANALYST, ReviewStatus.IN_PROGRESS));

        // ANALYST 试图把 REVIEW 覆盖为 APPROVE -> 拒绝
        ApiException e = assertThrows(ApiException.class,
                () -> reviewService.transition(id, action("ana1", Role.ANALYST, ReviewStatus.RESOLVED_APPROVE)));
        assertEquals(HttpStatus.FORBIDDEN, e.status());
        assertEquals(ReviewStatus.IN_PROGRESS, reviewStore.findById(id).orElseThrow().status());

        // SUPERVISOR 覆盖 -> 允许
        ReviewCase resolved = reviewService.transition(id,
                action("sup1", Role.SUPERVISOR, ReviewStatus.RESOLVED_APPROVE));
        assertEquals(ReviewStatus.RESOLVED_APPROVE, resolved.status());

        // 越权尝试已留痕
        assertTrue(auditLog.events().stream().anyMatch(ev ->
                ev.action().equals("PERMISSION_DENIED") && ev.actor().equals("ana1")));
        assertTrue(auditLog.verify());
    }

    @Test
    @DisplayName("ANALYST 可给出与系统一致的结论，但不能归档")
    void analystConsistentResolutionButNoClose() {
        // 命中名单(50)+大额(40)=90 -> 系统 BLOCK
        TransactionStore ts = new TransactionStore();
        WatchlistStore ws = new WatchlistStore();
        ws.add("ACC-BAD", "涉诈");
        ReviewStore rs = new ReviewStore();
        AuditLog al = new AuditLog();
        TransactionService svc = new TransactionService(ts, ws, rs, al,
                new RiskRuleEngine(List.of(new AmountRule(), new VelocityRule(), new WatchlistRule())));
        ReviewService rsvc = new ReviewService(rs, ts, al);
        svc.ingest(new TransactionRequest("TX-R5", "ACC-BAD", new BigDecimal("15000"),
                "CNY", "M-1", "APP", Instant.now().toString()));
        ReviewCase c = rs.findByTransactionId("TX-R5").orElseThrow();
        assertEquals(Decision.BLOCK, ts.findById("TX-R5").orElseThrow().assessment().decision());

        rsvc.transition(c.caseId(), action("ana1", Role.ANALYST, ReviewStatus.IN_PROGRESS));
        // 与系统一致（BLOCK->RESOLVED_BLOCK）：ANALYST 可以
        ReviewCase resolved = rsvc.transition(c.caseId(),
                action("ana1", Role.ANALYST, ReviewStatus.RESOLVED_BLOCK));
        assertEquals(ReviewStatus.RESOLVED_BLOCK, resolved.status());
        // 但归档需要 SUPERVISOR
        ApiException e = assertThrows(ApiException.class,
                () -> rsvc.transition(c.caseId(), action("ana1", Role.ANALYST, ReviewStatus.CLOSED)));
        assertEquals(HttpStatus.FORBIDDEN, e.status());
    }

    @Test
    @DisplayName("AUDITOR 只读：任何迁移都被拒绝并留痕")
    void auditorReadOnly() {
        ReviewCase c = openCaseFromBigAmount("TX-R6");
        ApiException e = assertThrows(ApiException.class,
                () -> reviewService.transition(c.caseId(), action("aud1", Role.AUDITOR, ReviewStatus.IN_PROGRESS)));
        assertEquals(HttpStatus.FORBIDDEN, e.status());
        assertTrue(auditLog.events().stream().anyMatch(ev ->
                ev.action().equals("PERMISSION_DENIED") && ev.actor().equals("aud1")));
        assertTrue(auditLog.verify());
    }

    @Test
    @DisplayName("工单不存在返回 404")
    void caseNotFound() {
        ApiException e = assertThrows(ApiException.class,
                () -> reviewService.transition("CASE-NOPE", action("sup1", Role.SUPERVISOR, ReviewStatus.IN_PROGRESS)));
        assertEquals(HttpStatus.NOT_FOUND, e.status());
    }
}
