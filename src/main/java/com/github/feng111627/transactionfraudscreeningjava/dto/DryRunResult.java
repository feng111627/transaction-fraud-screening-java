package com.github.feng111627.transactionfraudscreeningjava.dto;

import com.github.feng111627.transactionfraudscreeningjava.domain.Decision;

import java.time.Instant;
import java.util.List;

/**
 * 影响预演结果：用候选策略对历史交易（固定其当时的判定输入）重算，
 * 统计结论变化情况。预演只读：不落库、不计数、不开工单、不影响实时判定。
 */
public record DryRunResult(
        String candidateVersion,
        int totalTransactions,
        int changedCount,
        int unchangedCount,
        List<DecisionChange> samples,
        String operator,
        Instant dryRunAt
) {
    /** 一笔结论发生变化的交易样例。 */
    public record DecisionChange(
            String transactionId,
            int fromScore,
            Decision fromDecision,
            int toScore,
            Decision toDecision
    ) {
    }
}
