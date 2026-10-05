package com.github.feng111627.transactionfraudscreeningjava.domain;

/**
 * 一条被命中的风险规则及其解释。
 */
public record RuleHit(
        String ruleId,
        String ruleName,
        int score,
        String reason
) {
}
