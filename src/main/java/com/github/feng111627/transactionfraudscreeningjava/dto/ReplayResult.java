package com.github.feng111627.transactionfraudscreeningjava.dto;

import com.github.feng111627.transactionfraudscreeningjava.domain.Decision;

import java.time.Instant;
import java.util.List;

/**
 * 重算核对结果：按交易当时生效的策略版本与名单状态重新计算，
 * 与落库的原始结论逐项比对。重算不修改任何业务数据。
 */
public record ReplayResult(
        String transactionId,
        String policyVersion,
        long watchlistVersion,
        int originalScore,
        Decision originalDecision,
        List<String> originalHits,
        int replayedScore,
        Decision replayedDecision,
        List<String> replayedHits,
        boolean consistent,
        String operator,
        Instant replayedAt
) {
}
