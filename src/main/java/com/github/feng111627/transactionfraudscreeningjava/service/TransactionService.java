package com.github.feng111627.transactionfraudscreeningjava.service;

import com.github.feng111627.transactionfraudscreeningjava.error.ConflictException;
import com.github.feng111627.transactionfraudscreeningjava.error.ValidationException;
import com.github.feng111627.transactionfraudscreeningjava.ledger.Ledger;
import com.github.feng111627.transactionfraudscreeningjava.model.AuditAction;
import com.github.feng111627.transactionfraudscreeningjava.model.AuditEvent;
import com.github.feng111627.transactionfraudscreeningjava.model.Disposition;
import com.github.feng111627.transactionfraudscreeningjava.model.RejectReason;
import com.github.feng111627.transactionfraudscreeningjava.model.ReviewRecord;
import com.github.feng111627.transactionfraudscreeningjava.model.RiskEvaluation;
import com.github.feng111627.transactionfraudscreeningjava.model.TxnRecord;
import com.github.feng111627.transactionfraudscreeningjava.model.TxnStatus;
import com.github.feng111627.transactionfraudscreeningjava.rule.RiskEngine;
import com.github.feng111627.transactionfraudscreeningjava.web.dto.Dtos.SubmitTxnRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 交易接入服务：字段校验（按原因拒绝）、requestId 幂等、单笔/批量落账与风险判定编排。
 */
@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    /** 交易时间允许的未来偏差（毫秒） */
    private static final long FUTURE_TOLERANCE_MILLIS = 60_000L;
    /** 交易时间最早可接受 30 天前 */
    private static final long MAX_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000;

    private final Ledger ledger;
    private final RiskEngine riskEngine;
    private final ServiceClock clock;

    public TransactionService(Ledger ledger, RiskEngine riskEngine, ServiceClock clock) {
        this.ledger = ledger;
        this.riskEngine = riskEngine;
        this.clock = clock;
    }

    /** 单笔提交：重复 requestId 直接返回首次结论（幂等），字段非法按原因抛异常。 */
    public TxnRecord submitSingle(SubmitTxnRequest req, String source, String operator) {
        long now = clock.nowMillis();
        // 幂等命中：直接返回既有记录，不重复计数/出结论
        if (req.requestId() != null && !req.requestId().isBlank()
                && ledger.existsRequestId(req.requestId())) {
            TxnRecord existing = ledger.findTxnByRequestId(req.requestId()).orElseThrow();
            if (existing.getStatus() == TxnStatus.REJECTED) {
                // 该 requestId 此前因非法被拒绝：重放当初的明确原因
                throw new ValidationException(existing.getRejectReason().name(),
                        "该 requestId 此前已被拒绝，原因=" + existing.getRejectReason());
            }
            if (!samePayload(existing, req)) {
                ledger.appendAudit(new AuditEvent(0, now, AuditAction.TXN_REJECTED, operator,
                        req.requestId(), RejectReason.IDEMPOTENCY_MISMATCH.name(), "REJECTED"));
                throw new ConflictException(RejectReason.IDEMPOTENCY_MISMATCH.name(),
                        "requestId=" + req.requestId() + " 已存在但提交内容与首次不一致");
            }
            log.info("幂等命中 requestId={} txnId={} 直接返回首次结论", req.requestId(), existing.getTxnId());
            return existing;
        }

        TxnRecord record;
        try {
            validate(req, now);
        } catch (ValidationException e) {
            // 非法交易明确拒绝并留痕：有 requestId 的落账（REJECTED + 原因，不进入风控计数）
            if (req != null && req.requestId() != null && !req.requestId().isBlank()) {
                TxnRecord rejected = TxnRecord.rejected(req.requestId().trim(),
                        req.accountId(), now, source,
                        RejectReason.valueOf(e.getCode()));
                ledger.insertTxnIfAbsent(rejected);
            }
            ledger.appendAudit(new AuditEvent(0, now, AuditAction.TXN_REJECTED, operator,
                    req == null ? null : req.requestId(), e.getCode() + ":" + e.getMessage(), "REJECTED"));
            throw e;
        }

        record = TxnRecord.accepted(req.requestId(), ledger.nextTxnId(),
                req.accountId().trim(), req.targetId().trim(),
                req.amount(), req.currency().trim(),
                req.txnTimeEpochMilli(), now, source);

        TxnRecord stored = ledger.insertTxnIfAbsent(record);
        if (stored != record) {
            // 与并发请求竞争落账失败：返回既有记录（幂等）
            log.info("并发幂等命中 requestId={}", req.requestId());
            return stored;
        }

        evaluateAndAudit(stored, AuditAction.TXN_SUBMIT_SINGLE, operator, source);
        return stored;
    }

    /** 批量提交：逐条独立处理，非法项按原因拒绝且不影响其他项，全部给出结论。 */
    public List<TxnRecord> submitBatch(List<SubmitTxnRequest> requests, String operator) {
        long now = clock.nowMillis();
        if (requests == null || requests.isEmpty()) {
            throw new ValidationException("BATCH_EMPTY", "批量提交不能为空");
        }
        List<TxnRecord> results = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            SubmitTxnRequest req = requests.get(i);
            try {
                results.add(submitSingle(req, "BATCH#" + i, operator));
            } catch (ValidationException e) {
                log.warn("批量第 {} 项被拒绝 reason={} requestId={}", i, e.getCode(),
                        req != null ? req.requestId() : null);
                results.add(TxnRecord.rejected(
                        req != null && req.requestId() != null ? req.requestId() : "batch#" + i,
                        req != null ? req.accountId() : null, now, "BATCH#" + i,
                        RejectReason.valueOf(e.getCode())));
            } catch (ConflictException e) {
                TxnRecord existing = ledger.findTxnByRequestId(req.requestId()).orElseThrow();
                results.add(existing);
            }
        }
        ledger.appendAudit(new AuditEvent(0, now, AuditAction.TXN_SUBMIT_BATCH, operator, null,
                "批量提交 " + requests.size() + " 笔", "ACCEPTED"));
        return results;
    }

    public TxnRecord requireByTxnId(String txnId) {
        return ledger.findTxnByTxnId(txnId)
                .orElseThrow(() -> new com.github.feng111627.transactionfraudscreeningjava.error.NotFoundException(
                        "TXN_NOT_FOUND", "交易不存在: " + txnId));
    }

    public TxnRecord requireByRequestId(String requestId) {
        return ledger.findTxnByRequestId(requestId)
                .orElseThrow(() -> new com.github.feng111627.transactionfraudscreeningjava.error.NotFoundException(
                        "TXN_NOT_FOUND", "交易不存在 requestId=" + requestId));
    }

    /** 在账户锁内完成判定、挂载结论，并在 REVIEW 时自动开复核单。 */
    private void evaluateAndAudit(TxnRecord txn, AuditAction submitAction, String operator, String source) {
        long now = clock.nowMillis();
        ledger.evaluateTxn(txn, () -> {
            // 在账户锁内、判定开始时分配全局处理序号：这是频率窗口"前序交易"的唯一排序依据
            txn.markProcessed(ledger.nextProcessedSeq(), now);
            RiskEvaluation evaluation = riskEngine.evaluateFresh(txn, now);
            txn.attachEvaluation(evaluation);
            return txn;
        });

        RiskEvaluation ev = txn.getEvaluation();
        ledger.appendAudit(new AuditEvent(0, now, AuditAction.RISK_EVALUATED, operator,
                txn.getTxnId(),
                String.format("score=%d disposition=%s hits=%s configVersion=%d",
                        ev.totalScore(), ev.disposition(),
                        ev.hits().stream().map(h -> h.rule().name()).toList(),
                        ev.configVersion()),
                ev.disposition().name()));
        log.info("交易判定 txnId={} account={} amount={} score={} disposition={} hits={}",
                txn.getTxnId(), txn.getAccountId(), txn.getAmount(),
                ev.totalScore(), ev.disposition(),
                ev.hits().stream().map(h -> h.rule().name()).toList());

        if (ev.disposition() == Disposition.REVIEW
                && ledger.findReviewByTxnId(txn.getTxnId()).isEmpty()) {
            ReviewRecord review = ReviewRecord.open(
                    ledger.nextReviewId(), txn.getTxnId(), Disposition.REVIEW, now);
            ledger.saveReview(review);
            ledger.appendAudit(new AuditEvent(0, now, AuditAction.REVIEW_CREATED, operator,
                    review.getReviewId(), "系统判定转人工复核 txnId=" + txn.getTxnId(), "PENDING"));
            log.info("自动创建复核单 reviewId={} txnId={}", review.getReviewId(), txn.getTxnId());
        }
    }

    private void validate(SubmitTxnRequest req, long now) {
        if (req == null) {
            throw new ValidationException("REQUEST_NULL", "交易内容为空");
        }
        if (isBlank(req.requestId())) {
            throw reject(RejectReason.REQUEST_ID_BLANK, "requestId 不能为空");
        }
        if (isBlank(req.accountId())) {
            throw reject(RejectReason.ACCOUNT_ID_BLANK, "accountId 不能为空");
        }
        if (isBlank(req.targetId())) {
            throw reject(RejectReason.TARGET_ID_BLANK, "targetId 不能为空");
        }
        if (req.amount() == null) {
            throw reject(RejectReason.AMOUNT_NULL, "amount 不能为空");
        }
        if (req.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw reject(RejectReason.AMOUNT_NOT_POSITIVE,
                    "amount 必须为正数，实际=" + req.amount().toPlainString());
        }
        if (isBlank(req.currency())) {
            throw reject(RejectReason.CURRENCY_BLANK, "currency 不能为空");
        }
        if (req.txnTimeEpochMilli() == null) {
            throw reject(RejectReason.TXN_TIME_NULL, "txnTimeEpochMilli 不能为空");
        }
        if (req.txnTimeEpochMilli() > now + FUTURE_TOLERANCE_MILLIS) {
            throw reject(RejectReason.TXN_TIME_IN_FUTURE,
                    "交易时间不能晚于当前时间，txnTime=" + req.txnTimeEpochMilli());
        }
        if (req.txnTimeEpochMilli() < now - MAX_AGE_MILLIS) {
            throw reject(RejectReason.TXN_TIME_TOO_OLD,
                    "交易时间早于 30 天前，txnTime=" + req.txnTimeEpochMilli());
        }
    }

    private boolean samePayload(TxnRecord existing, SubmitTxnRequest req) {
        if (existing.getStatus() != TxnStatus.ACCEPTED) {
            return false;
        }
        return existing.getAccountId().equals(req.accountId().trim())
                && existing.getTargetId().equals(req.targetId().trim())
                && existing.getAmount().compareTo(req.amount()) == 0
                && existing.getCurrency().equals(req.currency().trim())
                && existing.getTxnTimeEpochMilli() == req.txnTimeEpochMilli();
    }

    private static ValidationException reject(RejectReason reason, String message) {
        return new ValidationException(reason.name(), message);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
