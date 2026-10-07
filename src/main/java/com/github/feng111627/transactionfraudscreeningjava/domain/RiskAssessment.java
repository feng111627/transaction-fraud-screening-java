package com.github.feng111627.transactionfraudscreeningjava.domain;

import java.time.Instant;
import java.util.List;

/**
 * 一次风险判定的完整结果。保存规则集版本与阈值快照，保证历史结论可复现。
 * rulesVersion / watchlistVersion 是结构化版本号，供按当时策略重算时精确定位
 * 判定所用的策略版本与名单状态；ruleSetVersion 为可读的合并标签。
 */
public record RiskAssessment(
        String transactionId,
        int totalScore,
        Decision decision,
        List<RuleHit> hits,
        String ruleSetVersion,
        String rulesVersion,
        long watchlistVersion,
        Instant assessedAt
) {
}
