package com.github.feng111627.transactionfraudscreeningjava.model;

import java.math.BigDecimal;

/** 风险规则阈值与计分口径的不可变快照 */
public record RuleConfig(
        long version,
        BigDecimal largeAmount,
        int largeAmountScore,
        long frequencyWindowSeconds,
        int frequencyLimit,
        int frequencyScore,
        int watchAccountScore,
        int watchTargetScore,
        int newPayeeScore,
        int reviewThreshold,
        int rejectThreshold
) {
}
