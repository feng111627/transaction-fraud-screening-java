package com.github.feng111627.transactionfraudscreeningjava.rules;

import com.github.feng111627.transactionfraudscreeningjava.domain.RuleHit;

import java.util.Optional;

/**
 * 单条风险规则。
 */
public interface RiskRule {

    String id();

    Optional<RuleHit> evaluate(RuleContext context, RuleConfig config);
}
