package com.github.feng111627.transactionfraudscreeningjava.service;

import com.github.feng111627.transactionfraudscreeningjava.domain.Decision;
import com.github.feng111627.transactionfraudscreeningjava.domain.ReviewCase;
import com.github.feng111627.transactionfraudscreeningjava.domain.ReviewStatus;
import com.github.feng111627.transactionfraudscreeningjava.domain.RiskAssessment;
import com.github.feng111627.transactionfraudscreeningjava.domain.Transaction;
import com.github.feng111627.transactionfraudscreeningjava.dto.IngestResult;
import com.github.feng111627.transactionfraudscreeningjava.dto.TransactionRequest;
import com.github.feng111627.transactionfraudscreeningjava.rules.RiskRuleEngine;
import com.github.feng111627.transactionfraudscreeningjava.rules.RuleConfig;
import com.github.feng111627.transactionfraudscreeningjava.rules.RuleContext;
import com.github.feng111627.transactionfraudscreeningjava.store.AuditLog;
import com.github.feng111627.transactionfraudscreeningjava.store.ReviewStore;
import com.github.feng111627.transactionfraudscreeningjava.store.TransactionStore;
import com.github.feng111627.transactionfraudscreeningjava.store.WatchlistStore;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 交易接入服务：校验、幂等、风险评估、落库、审计。
 *
 * - 字段缺失或非法：按具体原因拒绝（REJECTED + rejectReason），不落库、不计数、有审计；
 * - 重复提交：返回首次结论（DUPLICATE），不重复评估、不重复计数；
 * - 结论为 REVIEW/BLOCK 时自动开立复核工单。
 */
@Service
public class TransactionService {

    private final TransactionStore transactionStore;
    private final WatchlistStore watchlistStore;
    private final ReviewStore reviewStore;
    private final AuditLog auditLog;
    private final RiskRuleEngine ruleEngine;

    public TransactionService(TransactionStore transactionStore,
                              WatchlistStore watchlistStore,
                              ReviewStore reviewStore,
                              AuditLog auditLog,
                              RiskRuleEngine ruleEngine) {
        this.transactionStore = transactionStore;
        this.watchlistStore = watchlistStore;
        this.reviewStore = reviewStore;
        this.auditLog = auditLog;
        this.ruleEngine = ruleEngine;
    }

    public IngestResult ingest(TransactionRequest request) {
        List<String> errors = validate(request);
        if (!errors.isEmpty()) {
            String reason = String.join("; ", errors);
            auditLog.append("system", "TRANSACTION_REJECTED",
                    String.valueOf(request.transactionId()), reason);
            return new IngestResult(request.transactionId(),
                    IngestResult.IngestStatus.REJECTED, reason, null);
        }

        Transaction tx = toTransaction(request);
        AtomicReference<RiskAssessment> created = new AtomicReference<>();
        TransactionStore.StoredTransaction stored = transactionStore.saveIfAbsent(tx, () -> {
            RiskAssessment assessment = assess(tx);
            created.set(assessment);
            return assessment;
        });

        if (stored.assessment() != created.get()) {
            // 已存在同 ID 交易：返回首次结论，不重复计数
            auditLog.append("system", "TRANSACTION_DUPLICATE",
                    tx.transactionId(), "重复提交，返回首次结论");
            return new IngestResult(tx.transactionId(),
                    IngestResult.IngestStatus.DUPLICATE, null, stored.assessment());
        }

        RiskAssessment assessment = stored.assessment();
        auditLog.append("system", "TRANSACTION_ASSESSED", tx.transactionId(),
                "decision=" + assessment.decision()
                        + " score=" + assessment.totalScore()
                        + " rules=" + assessment.ruleSetVersion()
                        + " hits=" + assessment.hits().stream().map(h -> h.ruleId()).toList());

        if (assessment.decision() != Decision.APPROVE) {
            openReviewCase(tx, assessment);
        }
        return new IngestResult(tx.transactionId(),
                IngestResult.IngestStatus.ACCEPTED, null, assessment);
    }

    public List<IngestResult> ingestBatch(List<TransactionRequest> requests) {
        List<IngestResult> results = new ArrayList<>();
        for (TransactionRequest request : requests) {
            results.add(ingest(request));
        }
        return results;
    }

    private RiskAssessment assess(Transaction tx) {
        // 一次判定只取一份策略快照与一份名单快照，全程使用，
        // 保证发布切换时同一笔交易完整按某一个版本判定，不掺新旧规则。
        RuleConfig cfg = ruleEngine.currentConfig();
        Instant from = tx.occurredAt().minusSeconds(cfg.velocityWindowSeconds());
        long recentCount = transactionStore.countByAccountInWindow(tx.accountId(), from, tx.occurredAt());
        WatchlistStore.WatchlistSnapshot watchlist = watchlistStore.snapshot(tx.accountId());
        RuleContext context = new RuleContext(tx, recentCount, watchlist.entry());
        return ruleEngine.assess(context, cfg, watchlist.version());
    }

    private void openReviewCase(Transaction tx, RiskAssessment assessment) {
        String caseId = "CASE-" + UUID.randomUUID();
        ReviewCase reviewCase = new ReviewCase(caseId, tx.transactionId(),
                ReviewStatus.OPEN, null, null, Instant.now(), Instant.now());
        reviewStore.save(reviewCase);
        auditLog.append("system", "REVIEW_CASE_OPENED", caseId,
                "transaction=" + tx.transactionId() + " decision=" + assessment.decision());
    }

    private List<String> validate(TransactionRequest r) {
        List<String> errors = new ArrayList<>();
        if (r == null) {
            errors.add("请求体为空");
            return errors;
        }
        if (isBlank(r.transactionId())) {
            errors.add("缺少字段: transactionId");
        }
        if (isBlank(r.accountId())) {
            errors.add("缺少字段: accountId");
        }
        if (r.amount() == null) {
            errors.add("缺少字段: amount");
        } else if (r.amount().compareTo(BigDecimal.ZERO) <= 0) {
            errors.add("非法金额: amount 必须为正数");
        }
        if (isBlank(r.currency())) {
            errors.add("缺少字段: currency");
        }
        if (isBlank(r.occurredAt())) {
            errors.add("缺少字段: occurredAt");
        } else {
            try {
                Instant.parse(r.occurredAt());
            } catch (DateTimeParseException e) {
                errors.add("非法时间格式: occurredAt 必须为 ISO-8601，例如 2026-10-06T12:00:00Z");
            }
        }
        return errors;
    }

    private Transaction toTransaction(TransactionRequest r) {
        return new Transaction(
                r.transactionId(),
                r.accountId(),
                r.amount(),
                r.currency(),
                r.merchantId(),
                r.channel(),
                Instant.parse(r.occurredAt()));
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
