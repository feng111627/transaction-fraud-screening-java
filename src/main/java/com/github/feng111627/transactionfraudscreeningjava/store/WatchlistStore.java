package com.github.feng111627.transactionfraudscreeningjava.store;

import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存名单库。名单带单调递增版本号，每次变更版本 +1；
 * 判定结果记录判定时的名单版本与命中快照，名单更新后历史结论仍可复现。
 */
@Repository
public class WatchlistStore {

    public record WatchlistEntry(String accountId, String reason, Instant addedAt) {
    }

    /** 名单状态快照：版本号与命中条目在同一临界区读取，保证二者一致。 */
    public record WatchlistSnapshot(long version, WatchlistEntry entry) {
    }

    private final Map<String, WatchlistEntry> entries = new ConcurrentHashMap<>();
    private final AtomicLong version = new AtomicLong(0);

    public Optional<WatchlistEntry> find(String accountId) {
        return Optional.ofNullable(entries.get(accountId));
    }

    /** 原子读取某账户的名单状态与对应版本号，判定快照使用。 */
    public synchronized WatchlistSnapshot snapshot(String accountId) {
        return new WatchlistSnapshot(version.get(), entries.get(accountId));
    }

    public synchronized void add(String accountId, String reason) {
        entries.put(accountId, new WatchlistEntry(accountId, reason, Instant.now()));
        version.incrementAndGet();
    }

    public synchronized void remove(String accountId) {
        if (entries.remove(accountId) != null) {
            version.incrementAndGet();
        }
    }

    /** 每次名单变更版本号递增。 */
    public long version() {
        return version.get();
    }
}
