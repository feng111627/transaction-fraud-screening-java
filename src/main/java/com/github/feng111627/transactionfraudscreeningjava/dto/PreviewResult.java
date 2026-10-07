package com.github.feng111627.transactionfraudscreeningjava.dto;

import com.github.feng111627.transactionfraudscreeningjava.domain.Decision;

import java.util.List;

/**
 * 影响预演结果：候选策略对全部历史交易试算后，
 * 有多少笔结论会发生变化，以及典型的变化样本（从什么结论变成什么结论）。
 */
public record PreviewResult(
        String candidateVersion,
        int totalTransactions,
        int changedCount,
        int unchangedCount,
        List<DecisionChange> samples
) {
    /** 单笔结论变化样本。 */
    public record DecisionChange(
            String transactionId,
            Decision fromDecision,
            Decision toDecision,
            int fromScore,
            int toScore
    ) {
    }
}
