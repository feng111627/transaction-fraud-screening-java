package com.github.feng111627.transactionfraudscreeningjava.rules;

import com.github.feng111627.transactionfraudscreeningjava.domain.RuleHit;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 大额交易规则：单笔金额 >= highAmountThreshold 时加 highAmountScore 分。
 */
@Component
public class AmountRule implements RiskRule {

    @Override
    public String id() {
        return "AMOUNT_HIGH";
    }

    @Override
    public Optional<RuleHit> evaluate(RuleContext context, RuleConfig config) {
        if (context.transaction().amount().compareTo(config.highAmountThreshold()) >= 0) {
            return Optional.of(new RuleHit(
                    id(),
                    "大额交易",
                    config.highAmountScore(),
                    "金额 " + context.transaction().amount() + " 达到阈值 " + config.highAmountThreshold()));
        }
        return Optional.empty();
    }
}
