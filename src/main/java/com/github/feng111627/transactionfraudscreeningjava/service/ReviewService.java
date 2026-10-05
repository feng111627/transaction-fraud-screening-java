package com.github.feng111627.transactionfraudscreeningjava.service;

import com.github.feng111627.transactionfraudscreeningjava.error.AccessDeniedException;
import com.github.feng111627.transactionfraudscreeningjava.error.ConflictException;
import com.github.feng111627.transactionfraudscreeningjava.error.NotFoundException;
import com.github.feng111627.transactionfraudscreeningjava.ledger.Ledger;
import com.github.feng111627.transactionfraudscreeningjava.model.AuditAction;
import com.github.feng111627.transactionfraudscreeningjava.model.AuditEvent;
import com.github.feng111627.transactionfraudscreeningjava.model.Disposition;
import com.github.feng111627.transactionfraudscreeningjava.model.Operator;
import com.github.feng111627.transactionfraudscreeningjava.model.ReviewRecord;
import com.github.feng111627.transactionfraudscreeningjava.model.ReviewStatus;
import com.github.feng111627.transactionfraudscreeningjava.model.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 人工复核服务。
 * <p>状态机：{@code PENDING -> INVESTIGATING(claim) -> RESOLVED(resolve)}，其余迁移一律拒绝。
 * <p>权限：
 * <ul>
 *   <li>认领（claim）：ANALYST 及以上；</li>
 *   <li>按系统建议结案（APPROVE/REJECT，不覆盖）：ANALYST 及以上，且须是认领人；</li>
 *   <li>覆盖系统判断（final 与系统结论不同）：仅 SENIOR_ANALYST / ADMIN，且须是认领人。</li>
 * </ul>
 * 越权操作会被拒绝并写入审计（ACCESS_DENIED）。
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private final Ledger ledger;
    private final ServiceClock clock;

    public ReviewService(Ledger ledger, ServiceClock clock) {
        this.ledger = ledger;
        this.clock = clock;
    }

    /** PENDING -> INVESTIGATING */
    public ReviewRecord claim(String reviewId, Operator operator) {
        long now = clock.nowMillis();
        ReviewRecord review = mustGet(reviewId);
        if (!hasAnyRole(operator, Role.ANALYST, Role.SENIOR_ANALYST, Role.ADMIN)) {
            deny(operator, reviewId, "认领复核单需要 ANALYST 及以上角色");
        }
        if (review.getStatus() != ReviewStatus.PENDING) {
            throw new ConflictException("ILLEGAL_TRANSITION",
                    String.format("仅 PENDING 状态可认领，当前状态=%s", review.getStatus()));
        }
        review.claim(operator.name(), now);
        ledger.saveReview(review);
        ledger.appendAudit(new AuditEvent(0, now, AuditAction.REVIEW_CLAIM, operator.name(),
                reviewId, "认领复核单", "INVESTIGATING"));
        log.info("复核单认领 reviewId={} by={}", reviewId, operator.name());
        return review;
    }

    /** INVESTIGATING -> RESOLVED；finalDisposition 与系统结论不同视为覆盖，需高级权限。 */
    public ReviewRecord resolve(String reviewId, Disposition finalDisposition, String comment,
                                Operator operator) {
        long now = clock.nowMillis();
        ReviewRecord review = mustGet(reviewId);
        if (finalDisposition == null) {
            throw new com.github.feng111627.transactionfraudscreeningjava.error.ValidationException(
                    "FINAL_DISPOSITION_NULL", "结案结论不能为空");
        }
        if (review.getStatus() != ReviewStatus.INVESTIGATING) {
            throw new ConflictException("ILLEGAL_TRANSITION",
                    String.format("仅 INVESTIGATING 状态可结案，当前状态=%s", review.getStatus()));
        }
        if (!operator.name().equals(review.getClaimedBy())) {
            deny(operator, reviewId,
                    "只有认领人可以结案，认领人=" + review.getClaimedBy());
        }
        boolean override = finalDisposition != review.getSystemDisposition();
        if (override && !hasAnyRole(operator, Role.SENIOR_ANALYST, Role.ADMIN)) {
            deny(operator, reviewId, String.format(
                    "覆盖系统判断（%s -> %s）需要 SENIOR_ANALYST 或 ADMIN 角色",
                    review.getSystemDisposition(), finalDisposition));
        }
        review.resolve(operator.name(), now, finalDisposition, comment, override);
        ledger.saveReview(review);
        ledger.appendAudit(new AuditEvent(0, now,
                override ? AuditAction.REVIEW_OVERRIDE : AuditAction.REVIEW_RESOLVE,
                operator.name(), reviewId,
                String.format("结案 final=%s system=%s override=%s comment=%s",
                        finalDisposition, review.getSystemDisposition(), override, comment),
                "RESOLVED"));
        log.info("复核单结案 reviewId={} by={} final={} override={}",
                reviewId, operator.name(), finalDisposition, override);
        return review;
    }

    public ReviewRecord get(String reviewId) {
        return mustGet(reviewId);
    }

    public List<ReviewRecord> list() {
        return ledger.allReviews();
    }

    private ReviewRecord mustGet(String reviewId) {
        return ledger.findReviewById(reviewId)
                .orElseThrow(() -> new NotFoundException("REVIEW_NOT_FOUND",
                        "复核单不存在: " + reviewId));
    }

    private boolean hasAnyRole(Operator operator, Role... roles) {
        for (Role r : roles) {
            if (operator.roles().contains(r)) {
                return true;
            }
        }
        return false;
    }

    /** 越权：拒绝并留痕，再抛出异常。 */
    private void deny(Operator operator, String reviewId, String reason) {
        ledger.appendAudit(new AuditEvent(0, clock.nowMillis(), AuditAction.ACCESS_DENIED,
                operator.name(), reviewId, reason, "DENIED"));
        log.warn("越权操作被拒绝 operator={} roles={} reviewId={} reason={}",
                operator.name(), operator.roles(), reviewId, reason);
        throw new AccessDeniedException(reason);
    }
}
