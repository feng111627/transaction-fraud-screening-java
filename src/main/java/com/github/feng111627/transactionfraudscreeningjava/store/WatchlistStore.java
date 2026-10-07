package com.github.feng111627.transactionfraudscreeningjava.store;

import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存名单库。名单带单调递增版本号，每次变更版本 +1；
 * 判定结果记录判定时的名单版本与命中快照，名单更新后历史结论仍可复现。
 *
 * 每个版本的完整名单内容都会保留一份不可变快照（history 按下标即版本号索引），
 * 重算历史交易时可精确还原"当时生效的名单状态"。
 */
@Repository
public class WatchlistStore {

    public record WatchlistEntry(String accountId, String reason, Instant addedAt) {
    }

    /** 某一版本名单的完整快照：版本号与内容一致地绑定在一起。 */
    public record WatchlistSnapshot(long version, Map<String, WatchlistEntry> entries) {
        public Optional<WatchlistEntry> find(String accountId) {
            return Optional.ofNullable(entries.get(accountId));
        }
    }

    private final Map<String, WatchlistEntry> entries = new ConcurrentHashMap<>();
    /** history.get(v) = 版本 v 的名单内容快照，history 的大小 - 1 即当前版本号。 */
    private final List<Map<String, WatchlistEntry>> history = new ArrayList<>();

    public WatchlistStore() {
        history.add(Map.of()); // v0：空名单
    }

    public Optional<WatchlistEntry> find(String accountId) {
        return Optional.ofNullable(entries.get(accountId));
    }

    public synchronized void add(String accountId, String reason) {
        entries.put(accountId, new WatchlistEntry(accountId, reason, Instant.now()));
        history.add(Map.copyOf(entries));
    }

    public synchronized boolean remove(String accountId) {
        if (entries.remove(accountId) == null) {
            return false;
        }
        history.add(Map.copyOf(entries));
        return true;
    }

    /** 当前版本号：每次名单变更版本号递增。 */
    public long version() {
        return history.size() - 1L;
    }

    /** 当前名单的原子快照：版本号与内容来自同一临界区，二者不会错位。 */
    public synchronized WatchlistSnapshot snapshot() {
        return new WatchlistSnapshot(version(), Map.copyOf(entries));
    }

    /** 指定历史版本的名单快照（重算用）。版本不存在时返回空。 */
    public synchronized Optional<WatchlistSnapshot> snapshotAt(long version) {
        if (version < 0 || version >= history.size()) {
            return Optional.empty();
        }
        return Optional.of(new WatchlistSnapshot(version, history.get((int) version)));
    }

    /** 全部当前名单条目（只读视图）。 */
    public Map<String, WatchlistEntry> entries() {
        return Map.copyOf(entries);
    }
}
