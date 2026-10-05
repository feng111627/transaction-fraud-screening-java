package com.github.feng111627.transactionfraudscreeningjava.rule;

import com.github.feng111627.transactionfraudscreeningjava.model.HitRule;
import com.github.feng111627.transactionfraudscreeningjava.model.RuleCode;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** R004 收款方命中名单：高风险，加 watchTargetScore。 */
@Component
public class WatchTargetRule implements RiskRule {

    @Override
    public Optional<HitRule> evaluate(RuleContext ctx) {
        if (ctx.targetWatchlisted()) {
            String detail = String.format("收款方 %s 命中风控名单", ctx.txn().getTargetId());
            return Optional.of(new HitRule(
                    RuleCode.R004_WATCHLIST_TARGET,
                    ctx.config().watchTargetScore(),
                    "收款方在名单中",
                    detail));
        }
        return Optional.empty();
    }

    @Override
    public String code() {
        return RuleCode.R004_WATCHLIST_TARGET.name();
    }
}
