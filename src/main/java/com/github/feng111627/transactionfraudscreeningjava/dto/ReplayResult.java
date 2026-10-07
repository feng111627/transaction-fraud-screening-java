package com.github.feng111627.transactionfraudscreeningjava.dto;

import com.github.feng111627.transactionfraudscreeningjava.domain.RiskAssessment;

/**
 * 决策重算结果：原始结论 vs 按当时策略与名单状态重算的结论。
 * consistent=true 表示分数、命中规则、结论三者完全一致。
 */
public record ReplayResult(
        String transactionId,
        String operator,
        boolean consistent,
        RiskAssessment original,
        RiskAssessment recomputed
) {
}
