package com.github.feng111627.transactionfraudscreeningjava.service;

import com.github.feng111627.transactionfraudscreeningjava.error.AccessDeniedException;
import com.github.feng111627.transactionfraudscreeningjava.error.NotFoundException;
import com.github.feng111627.transactionfraudscreeningjava.error.ValidationException;
import com.github.feng111627.transactionfraudscreeningjava.ledger.Ledger;
import com.github.feng111627.transactionfraudscreeningjava.model.AuditAction;
import com.github.feng111627.transactionfraudscreeningjava.model.AuditEvent;
import com.github.feng111627.transactionfraudscreeningjava.model.ListType;
import com.github.feng111627.transactionfraudscreeningjava.model.Operator;
import com.github.feng111627.transactionfraudscreeningjava.model.RiskEvaluation;
import com.github.feng111627.transactionfraudscreeningjava.model.Role;
import com.github.feng111627.transactionfraudscreeningjava.model.RuleConfig;
import com.github.feng111627.transactionfraudscreeningjava.model.TxnRecord;
import com.github.feng111627.transactionfraudscreeningjava.model.WatchEntry;
import com.github.feng111627.transactionfraudscreeningjava.rule.RiskEngine;
import com.github.feng111627.transactionfraudscreeningjava.web.dto.Dtos.RuleConfigRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 管理服务：名单维护、规则阈值/计分配置的版本化更新、历史结论复现、数据查询。
 * <p>权限：名单与配置的变更仅 ADMIN；查询（交易/审计/复现）SENIOR_ANALYST 及以上。
 */
@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    private final Ledger ledger;
    private final RiskEngine riskEngine;
    private final ServiceClock clock;

    public AdminService(Ledger ledger, RiskEngine riskEngine, ServiceClock clock) {
        this.ledger = ledger;
        this.riskEngine = riskEngine;
        this.clock = clock;
    }

    public WatchEntry addWatchEntry(String typeRaw, String subject, String reason, Operator operator) {
        requireRole(operator, Role.ADMIN, "变更名单需要 ADMIN 角色");
        long now = clock.nowMillis();
        ListType type = parseListType(typeRaw);
        if (subject == null || subject.isBlank()) {
            throw new ValidationException("SUBJECT_BLANK", "名单主体不能为空");
        }
        WatchEntry entry = ledger.upsertWatch(type, subject.trim(),
                reason == null ? "" : reason.trim(), operator.name(), now);
        ledger.appendAudit(new AuditEvent(0, now, AuditAction.WATCHLIST_UPDATED, operator.name(),
                type + ":" + subject, "加入/更新名单 reason=" + reason, "ACTIVE"));
        log.info("名单更新 type={} subject={} by={}", type, subject, operator.name());
        return entry;
    }

    /** 基于当前配置创建下一版本（只覆盖非空字段），历史版本保留以便复现。 */
    public RuleConfig updateRuleConfig(RuleConfigRequest req, Operator operator) {
        requireRole(operator, Role.ADMIN, "变更规则配置需要 ADMIN 角色");
        if (req == null) {
            throw new ValidationException("CONFIG_NULL", "配置内容为空");
        }
        RuleConfig cur = ledger.currentRuleConfig();
        RuleConfig next = new RuleConfig(
                cur.version() + 1,
                req.largeAmount() != null ? req.largeAmount() : cur.largeAmount(),
                req.largeAmountScore() != null ? req.largeAmountScore() : cur.largeAmountScore(),
                req.frequencyWindowSeconds() != null ? req.frequencyWindowSeconds() : cur.frequencyWindowSeconds(),
                req.frequencyLimit() != null ? req.frequencyLimit() : cur.frequencyLimit(),
                req.frequencyScore() != null ? req.frequencyScore() : cur.frequencyScore(),
                req.watchAccountScore() != null ? req.watchAccountScore() : cur.watchAccountScore(),
                req.watchTargetScore() != null ? req.watchTargetScore() : cur.watchTargetScore(),
                req.newPayeeScore() != null ? req.newPayeeScore() : cur.newPayeeScore(),
                req.reviewThreshold() != null ? req.reviewThreshold() : cur.reviewThreshold(),
                req.rejectThreshold() != null ? req.rejectThreshold() : cur.rejectThreshold());
        validateConfig(next);
        ledger.updateRuleConfig(next);
        ledger.appendAudit(new AuditEvent(0, clock.nowMillis(), AuditAction.RULE_CONFIG_UPDATED,
                operator.name(), "configVersion=" + next.version(),
                "阈值/计分配置更新", "v" + next.version()));
        log.info("规则配置发布新版本 version={} by={}", next.version(), operator.name());
        return next;
    }

    /** 复现历史结论：用原始 configVersion 重新跑引擎，并写 REPRODUCED 审计。 */
    public RiskEvaluation reproduce(String txnId, Operator operator) {
        requireRole(operator, Role.SENIOR_ANALYST, Role.ADMIN, "复现结论需要 SENIOR_ANALYST 及以上角色");
        TxnRecord txn = ledger.findTxnByTxnId(txnId)
                .orElseThrow(() -> new NotFoundException("TXN_NOT_FOUND", "交易不存在: " + txnId));
        if (txn.getEvaluation() == null) {
            throw new ValidationException("TXN_NOT_EVALUATED", "该交易没有历史判定可复现: " + txnId);
        }
        RiskEvaluation re = riskEngine.reproduce(txn);
        RiskEvaluation original = txn.getEvaluation();
        boolean consistent = re.totalScore() == original.totalScore()
                && re.disposition() == original.disposition()
                && re.hits().size() == original.hits().size();
        ledger.appendAudit(new AuditEvent(0, clock.nowMillis(), AuditAction.REPRODUCED,
                operator.name(), txnId,
                String.format("复现 score=%d/%d disposition=%s/%s consistent=%s configVersion=%d",
                        re.totalScore(), original.totalScore(),
                        re.disposition(), original.disposition(), consistent, re.configVersion()),
                consistent ? "CONSISTENT" : "DIFF"));
        log.info("结论复现 txnId={} consistent={} reproducedScore={} originalScore={}",
                txnId, consistent, re.totalScore(), original.totalScore());
        return re;
    }

    public RuleConfig currentConfig() {
        return ledger.currentRuleConfig();
    }

    public List<WatchEntry> listWatch() {
        return ledger.allWatchEntries();
    }

    public List<TxnRecord> listTxns() {
        return ledger.allTxns();
    }

    public TxnRecord getTxn(String txnId) {
        return ledger.findTxnByTxnId(txnId)
                .orElseThrow(() -> new NotFoundException("TXN_NOT_FOUND", "交易不存在: " + txnId));
    }

    public List<AuditEvent> audits() {
        return ledger.allAudits();
    }

    private ListType parseListType(String raw) {
        try {
            return ListType.valueOf(raw);
        } catch (Exception e) {
            throw new ValidationException("LIST_TYPE_INVALID",
                    "名单类型必须为 ACCOUNT 或 TARGET，实际=" + raw);
        }
    }

    private void validateConfig(RuleConfig c) {
        if (c.largeAmount().signum() <= 0) {
            throw new ValidationException("CONFIG_INVALID", "largeAmount 必须为正");
        }
        if (c.frequencyWindowSeconds() <= 0 || c.frequencyLimit() <= 0) {
            throw new ValidationException("CONFIG_INVALID", "频率窗口与阈值必须为正");
        }
        if (c.reviewThreshold() <= 0 || c.rejectThreshold() < c.reviewThreshold()) {
            throw new ValidationException("CONFIG_INVALID",
                    "需要 reviewThreshold>0 且 rejectThreshold>=reviewThreshold");
        }
        if (c.largeAmountScore() < 0 || c.frequencyScore() < 0 || c.watchAccountScore() < 0
                || c.watchTargetScore() < 0 || c.newPayeeScore() < 0) {
            throw new ValidationException("CONFIG_INVALID", "规则分值不能为负");
        }
    }

    private void requireRole(Operator operator, Role role, String reason) {
        if (!operator.roles().contains(role)) {
            deny(operator, reason);
        }
    }

    private void requireRole(Operator operator, Role r1, Role r2, String reason) {
        if (!operator.roles().contains(r1) && !operator.roles().contains(r2)) {
            deny(operator, reason);
        }
    }

    private void deny(Operator operator, String reason) {
        ledger.appendAudit(new AuditEvent(0, clock.nowMillis(), AuditAction.ACCESS_DENIED,
                operator.name(), null, reason, "DENIED"));
        log.warn("越权操作被拒绝 operator={} roles={} reason={}",
                operator.name(), operator.roles(), reason);
        throw new AccessDeniedException(reason);
    }
}
