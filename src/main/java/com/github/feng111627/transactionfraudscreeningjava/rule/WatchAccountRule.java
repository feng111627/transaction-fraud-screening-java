package com.github.feng111627.transactionfraudscreeningjava.rule;

import com.github.feng111627.transactionfraudscreeningjava.model.HitRule;
import com.github.feng111627.transactionfraudscreeningjava.model.RuleCode;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** R003 付款账户命中名单：高风险，加 watchAccountScore。 */
@Component
public class WatchAccountRule implements RiskRule {

    @Override
    public Optional<HitRule> evaluate(RuleContext ctx) {
        if (ctx.accountWatchlisted()) {
            String detail = String.format("付款账户 %s 命中风控名单", ctx.txn().getAccountId());
            return Optional.of(new HitRule(
                    RuleCode.R003_WATCHLIST_ACCOUNT,
                    ctx.config().watchAccountScore(),
                    "付款账户在名单中",
                    detail));
        }
        return Optional.empty();
    }

    @Override
    public String code() {
        return RuleCode.R003_WATCHLIST_ACCOUNT.name();
    }
}
