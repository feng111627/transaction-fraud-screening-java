package com.github.feng111627.transactionfraudscreeningjava.service;

import com.github.feng111627.transactionfraudscreeningjava.domain.Decision;
import com.github.feng111627.transactionfraudscreeningjava.domain.ReviewCase;
import com.github.feng111627.transactionfraudscreeningjava.domain.ReviewStatus;
import com.github.feng111627.transactionfraudscreeningjava.domain.Role;
import com.github.feng111627.transactionfraudscreeningjava.dto.ReviewActionRequest;
import com.github.feng111627.transactionfraudscreeningjava.exception.ApiException;
import com.github.feng111627.transactionfraudscreeningjava.store.AuditLog;
import com.github.feng111627.transactionfraudscreeningjava.store.ReviewStore;
import com.github.feng111627.transactionfraudscreeningjava.store.TransactionStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 人工复核服务：状态机流转 + 权限校验 + 审计留痕。
 *
 * 状态机：
 *   OPEN -> IN_PROGRESS（认领）
 *   IN_PROGRESS -> RESOLVED_APPROVE / RESOLVED_BLOCK（复核结论）
 *   RESOLVED_* -> CLOSED（归档，仅 SUPERVISOR）
 * 其余迁移一律拒绝（409）。
 *
 * 权限：
 *   AUDITOR 只读，任何迁移都拒绝（403）；
 *   ANALYST 可认领、可给出与系统一致的结论；
 *   覆盖系统结论（与系统 decision 不一致的处置）仅 SUPERVISOR；
 *   CLOSED 归档仅 SUPERVISOR。
 * 越权与非法迁移都会被拒绝并写入审计。
 */
@Service
public class ReviewService {

    private static final Map<ReviewStatus, Set<ReviewStatus>> ALLOWED = Map.of(
            ReviewStatus.OPEN, EnumSet.of(ReviewStatus.IN_PROGRESS),
            ReviewStatus.IN_PROGRESS, EnumSet.of(ReviewStatus.RESOLVED_APPROVE, ReviewStatus.RESOLVED_BLOCK),
            ReviewStatus.RESOLVED_APPROVE, EnumSet.of(ReviewStatus.CLOSED),
            ReviewStatus.RESOLVED_BLOCK, EnumSet.of(ReviewStatus.CLOSED),
            ReviewStatus.CLOSED, EnumSet.noneOf(ReviewStatus.class)
    );

    private final ReviewStore reviewStore;
    private final TransactionStore transactionStore;
    private final AuditLog auditLog;
    private final ConcurrentHashMap<String, Object> caseLocks = new ConcurrentHashMap<>();

    public ReviewService(ReviewStore reviewStore, TransactionStore transactionStore, AuditLog auditLog) {
        this.reviewStore = reviewStore;
        this.transactionStore = transactionStore;
        this.auditLog = auditLog;
    }

    public ReviewCase transition(String caseId, ReviewActionRequest action) {
        Object lock = caseLocks.computeIfAbsent(caseId, k -> new Object());
        synchronized (lock) {
            ReviewCase current = reviewStore.findById(caseId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "复核工单不存在: " + caseId));

            if (action.role() == null || action.operator() == null || action.operator().isBlank()
                    || action.targetStatus() == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "operator/role/targetStatus 均不能为空");
            }

            // 1. 状态机校验
            if (!ALLOWED.get(current.status()).contains(action.targetStatus())) {
                auditLog.append(action.operator(), "REVIEW_TRANSITION_REJECTED", caseId,
                        "非法状态迁移: " + current.status() + " -> " + action.targetStatus());
                throw new ApiException(HttpStatus.CONFLICT,
                        "非法状态迁移: " + current.status() + " -> " + action.targetStatus());
            }

            // 2. 权限校验
            String permissionError = checkPermission(current, action);
            if (permissionError != null) {
                auditLog.append(action.operator(), "PERMISSION_DENIED", caseId, permissionError);
                throw new ApiException(HttpStatus.FORBIDDEN, permissionError);
            }

            ReviewCase updated = current.withStatus(action.targetStatus(),
                    action.operator(), action.note(), Instant.now());
            reviewStore.save(updated);
            auditLog.append(action.operator(), "REVIEW_TRANSITION", caseId,
                    current.status() + " -> " + action.targetStatus()
                            + (action.note() == null ? "" : " note=" + action.note()));
            return updated;
        }
    }

    private String checkPermission(ReviewCase current, ReviewActionRequest action) {
        Role role = action.role();
        if (role == Role.AUDITOR) {
            return "AUDITOR 为只读角色，不能执行复核操作";
        }
        if (role == Role.POLICY_ADMIN) {
            return "POLICY_ADMIN 为策略管理角色，不能执行复核操作";
        }
        ReviewStatus target = action.targetStatus();
        if (target == ReviewStatus.CLOSED && role != Role.SUPERVISOR) {
            return "归档(CLOSED)仅 SUPERVISOR 可执行";
        }
        if (target == ReviewStatus.RESOLVED_APPROVE || target == ReviewStatus.RESOLVED_BLOCK) {
            Decision systemDecision = systemDecision(current.transactionId());
            Decision humanDecision = target == ReviewStatus.RESOLVED_APPROVE ? Decision.APPROVE : Decision.BLOCK;
            boolean overridesSystem = systemDecision != null && humanDecision != systemDecision;
            if (overridesSystem && role != Role.SUPERVISOR) {
                return "覆盖系统结论(" + systemDecision + " -> " + humanDecision + ")仅 SUPERVISOR 可执行";
            }
        }
        return null;
    }

    private Decision systemDecision(String transactionId) {
        return transactionStore.findById(transactionId)
                .map(stored -> stored.assessment().decision())
                .orElse(null);
    }
}
