package com.github.feng111627.transactionfraudscreeningjava.rule;

import com.github.feng111627.transactionfraudscreeningjava.model.HitRule;

import java.util.Optional;

/** 单条风险规则：命中时返回 HitRule（编号、加分、原因与细节），未命中返回 empty。 */
public interface RiskRule {

    Optional<HitRule> evaluate(RuleContext ctx);

    /** 规则编号，便于日志与说明 */
    String code();
}
