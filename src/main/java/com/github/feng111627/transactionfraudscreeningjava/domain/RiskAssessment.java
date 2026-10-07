package com.github.feng111627.transactionfraudscreeningjava.domain;

import com.github.feng111627.transactionfraudscreeningjava.store.WatchlistStore;

import java.time.Instant;
import java.util.List;

/**
 * 一次风险判定的完整结果。保存规则集版本与阈值快照，保证历史结论可复现。
 *
 * 同时记录判定当时的输入快照（窗口内已有笔数、名单命中条目），
 * 使历史交易可以严格按"当时的策略版本 + 当时的输入"重算核对，
 * 不受后续新交易入账、名单变更的影响。
 */
public record RiskAssessment(
        String transactionId,
        int totalScore,
        Decision decision,
        List<RuleHit> hits,
        String ruleSetVersion,
        Instant assessedAt,
        String policyVersion,
        long watchlistVersion,
        long recentCount,
        WatchlistStore.WatchlistEntry watchlistEntry
) {
}
