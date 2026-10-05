package com.github.feng111627.transactionfraudscreeningjava.rules;

import com.github.feng111627.transactionfraudscreeningjava.domain.Transaction;
import com.github.feng111627.transactionfraudscreeningjava.store.WatchlistStore;

/**
 * 单次规则评估的上下文：交易本身 + 窗口内频率 + 名单命中情况。
 */
public record RuleContext(
        Transaction transaction,
        long recentCount,
        WatchlistStore.WatchlistEntry watchlistEntry
) {
    public boolean onWatchlist() {
        return watchlistEntry != null;
    }
}
