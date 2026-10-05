package com.github.feng111627.transactionfraudscreeningjava.rules;

import java.math.BigDecimal;

/**
 * 规则集配置（含版本号）。每次调整阈值产生新版本，
 * 判定结果中记录版本与配置快照，保证历史结论可复现。
 */
public record RuleConfig(
        String version,
        BigDecimal highAmountThreshold,
        int highAmountScore,
        int velocityWindowSeconds,
        int velocityMaxCount,
        int velocityScore,
        int watchlistScore,
        int reviewThreshold,
        int blockThreshold
) {
    public static RuleConfig defaultConfig() {
        return new RuleConfig(
                "v1",
                new BigDecimal("10000"),
                40,
                60,
                5,
                30,
                50,
                50,
                80
        );
    }
}
