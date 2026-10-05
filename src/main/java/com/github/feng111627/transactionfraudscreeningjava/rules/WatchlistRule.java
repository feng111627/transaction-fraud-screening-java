package com.github.feng111627.transactionfraudscreeningjava.rules;

import com.github.feng111627.transactionfraudscreeningjava.domain.RuleHit;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 名单规则：账户命中名单时加 watchlistScore 分，并说明命中原因。
 */
@Component
public class WatchlistRule implements RiskRule {

    @Override
    public String id() {
        return "WATCHLIST_HIT";
    }

    @Override
    public Optional<RuleHit> evaluate(RuleContext context, RuleConfig config) {
        if (context.onWatchlist()) {
            return Optional.of(new RuleHit(
                    id(),
                    "命中风险名单",
                    config.watchlistScore(),
                    "账户 " + context.watchlistEntry().accountId()
                            + " 在名单中，原因：" + context.watchlistEntry().reason()));
        }
        return Optional.empty();
    }
}
