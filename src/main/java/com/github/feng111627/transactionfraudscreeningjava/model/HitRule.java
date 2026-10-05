package com.github.feng111627.transactionfraudscreeningjava.model;

/** 单条命中的规则：编号、加分、命中原因、判定时快照细节 */
public record HitRule(
        RuleCode rule,
        int score,
        String reason,
        String detail
) {
}
