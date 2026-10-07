package com.github.feng111627.transactionfraudscreeningjava.service;

import com.github.feng111627.transactionfraudscreeningjava.domain.PolicyVersion;
import com.github.feng111627.transactionfraudscreeningjava.domain.RiskAssessment;
import com.github.feng111627.transactionfraudscreeningjava.dto.ReplayResult;
import com.github.feng111627.transactionfraudscreeningjava.exception.ApiException;
import com.github.feng111627.transactionfraudscreeningjava.rules.RiskRuleEngine;
import com.github.feng111627.transactionfraudscreeningjava.rules.RuleConfig;
import com.github.feng111627.transactionfraudscreeningjava.rules.RuleContext;
import com.github.feng111627.transactionfraudscreeningjava.store.AuditLog;
import com.github.feng111627.transactionfraudscreeningjava.store.PolicyStore;
import com.github.feng111627.transactionfraudscreeningjava.store.TransactionStore;
import com.github.feng111627.transactionfraudscreeningjava.store.WatchlistStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 历史决策重算服务：对一笔已落库交易，严格按它当初判定时的
 * 策略版本与名单状态重新计算，并与原始结论核对。
 *
 * 重算口径（与当初判定完全一致的三项输入）：
 * - 策略版本：取结论中记录的 rulesVersion 对应的已发布版本；
 * - 名单状态：取结论中记录的 watchlistVersion 对应的名单快照；
 * - 窗口频率：只统计"入账序号小于该交易"的同账户交易，
 *   即还原它当初判定时已经入账的交易集合。
 *
 * 重算是纯查询核对：不落库、不新增计数、不开工单、不覆盖原结论，
 * 只向审计日志追加一条重算记录（操作者、时间、是否一致）。
 */
@Service
public class ReplayService {

    private final TransactionStore transactionStore;
    private final WatchlistStore watchlistStore;
    private final PolicyStore policyStore;
    private final RiskRuleEngine ruleEngine;
    private final AuditLog auditLog;

    public ReplayService(TransactionStore transactionStore,
                         WatchlistStore watchlistStore,
                         PolicyStore policyStore,
                         RiskRuleEngine ruleEngine,
                         AuditLog auditLog) {
        this.transactionStore = transactionStore;
        this.watchlistStore = watchlistStore;
        this.policyStore = policyStore;
        this.ruleEngine = ruleEngine;
        this.auditLog = auditLog;
    }

    public ReplayResult recompute(String transactionId, String operator) {
        if (operator == null || operator.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "operator 不能为空");
        }
        TransactionStore.StoredTransaction stored = transactionStore.findById(transactionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "交易不存在: " + transactionId));
        RiskAssessment original = stored.assessment();

        PolicyVersion policy = policyStore.find(original.rulesVersion())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                        "原始判定使用的策略版本不存在: " + original.rulesVersion()));
        WatchlistStore.WatchlistSnapshot watchlist = watchlistStore.snapshotAt(original.watchlistVersion())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                        "原始判定使用的名单版本不存在: v" + original.watchlistVersion()));

        RiskAssessment recomputed = recomputeWith(stored, policy.config(), watchlist);
        boolean consistent = recomputed.totalScore() == original.totalScore()
                && recomputed.decision() == original.decision()
                && recomputed.hits().equals(original.hits());

        auditLog.append(operator, "DECISION_REPLAY", transactionId,
                "rulesVersion=" + original.rulesVersion()
                        + " watchlistVersion=v" + original.watchlistVersion()
                        + " consistent=" + consistent
                        + " original=" + original.decision() + "/" + original.totalScore()
                        + " recomputed=" + recomputed.decision() + "/" + recomputed.totalScore());
        return new ReplayResult(transactionId, operator, consistent, original, recomputed);
    }

    /**
     * 用指定策略配置与名单快照重算一笔已落库交易。
     * 窗口频率按"入账序号小于该交易"还原，预演（候选策略）也复用本方法。
     */
    RiskAssessment recomputeWith(TransactionStore.StoredTransaction stored,
                                 RuleConfig config,
                                 WatchlistStore.WatchlistSnapshot watchlist) {
        var tx = stored.transaction();
        Instant from = tx.occurredAt().minusSeconds(config.velocityWindowSeconds());
        long recentCount = transactionStore.countByAccountInWindowBefore(
                tx.accountId(), from, tx.occurredAt(), stored.seq());
        var entry = watchlist.find(tx.accountId()).orElse(null);
        RuleContext context = new RuleContext(tx, recentCount, entry);
        return ruleEngine.assess(context, config, watchlist.version());
    }
}
