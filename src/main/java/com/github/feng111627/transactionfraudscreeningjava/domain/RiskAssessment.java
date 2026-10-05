package com.github.feng111627.transactionfraudscreeningjava.domain;

import java.time.Instant;
import java.util.List;

/**
 * 一次风险判定的完整结果。保存规则集版本与阈值快照，保证历史结论可复现。
 */
public record RiskAssessment(
        String transactionId,
        int totalScore,
        Decision decision,
        List<RuleHit> hits,
        String ruleSetVersion,
        Instant assessedAt
) {
}
