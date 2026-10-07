package com.github.feng111627.transactionfraudscreeningjava.store;

import com.github.feng111627.transactionfraudscreeningjava.domain.RiskAssessment;
import com.github.feng111627.transactionfraudscreeningjava.domain.Transaction;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 内存交易账本：负责幂等落库、按账户的时间窗口统计。
 *
 * 并发约定：
 * - 同一 transactionId 通过双重检查 + 账户级锁保证只入账一次；
 * - 同一账户的"窗口计数 + 评估 + 入账"在账户级锁内串行完成，
 *   并发交易不会漏算或重复计算窗口频率。
 *
 * 每笔落库记录带全局单调递增的入账序号 seq。
 * 由于同账户的"计数 + 入账"串行完成，一笔交易判定当时的窗口计数
 * 恰好等于"窗口内且 seq 更小"的同账户交易笔数，因此历史交易可按此精确重算。
 */
@Repository
public class TransactionStore {

    private final ConcurrentHashMap<String, StoredTransaction> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> accountLocks = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(0);

    /**
     * 幂等写入：同一 transactionId 已存在时直接返回已有结果，不重复评估、不重复计数。
     * 双重检查 + 账户级锁：评估（含窗口计数）与入账在同一临界区内完成，
     * 保证同账户并发交易彼此可见，不漏算不重复计算。
     */
    public StoredTransaction saveIfAbsent(Transaction tx, Supplier<RiskAssessment> assessmentSupplier) {
        StoredTransaction existing = byId.get(tx.transactionId());
        if (existing != null) {
            return existing;
        }
        Object lock = accountLocks.computeIfAbsent(tx.accountId(), k -> new Object());
        synchronized (lock) {
            existing = byId.get(tx.transactionId());
            if (existing != null) {
                return existing;
            }
            RiskAssessment assessment = assessmentSupplier.get();
            StoredTransaction created = new StoredTransaction(tx, assessment, sequence.incrementAndGet());
            byId.put(tx.transactionId(), created);
            return created;
        }
    }

    public Optional<StoredTransaction> findById(String transactionId) {
        return Optional.ofNullable(byId.get(transactionId));
    }

    /** 统计某账户在 [from, to] 内已入账的交易笔数。 */
    public long countByAccountInWindow(String accountId, Instant from, Instant to) {
        Object lock = accountLocks.computeIfAbsent(accountId, k -> new Object());
        synchronized (lock) {
            return countInWindow(accountId, from, to, Long.MAX_VALUE);
        }
    }

    /**
     * 统计某账户在 [from, to] 内、入账序号严格小于 beforeSeq 的交易笔数。
     * 用于重算历史交易：还原它当初判定时"已经入账"的同账户交易集合。
     */
    public long countByAccountInWindowBefore(String accountId, Instant from, Instant to, long beforeSeq) {
        Object lock = accountLocks.computeIfAbsent(accountId, k -> new Object());
        synchronized (lock) {
            return countInWindow(accountId, from, to, beforeSeq);
        }
    }

    private long countInWindow(String accountId, Instant from, Instant to, long beforeSeq) {
        return byId.values().stream()
                .filter(st -> st.seq() < beforeSeq)
                .map(StoredTransaction::transaction)
                .filter(t -> t.accountId().equals(accountId))
                .filter(t -> !t.occurredAt().isBefore(from) && !t.occurredAt().isAfter(to))
                .count();
    }

    public List<StoredTransaction> findAll() {
        return List.copyOf(byId.values());
    }

    /** 全部落库记录，按入账序号升序（重算 / 预演遍历时保证确定性顺序）。 */
    public List<StoredTransaction> findAllOrdered() {
        return byId.values().stream()
                .sorted(Comparator.comparingLong(StoredTransaction::seq))
                .toList();
    }

    /** 落库记录：交易 + 其风险结论 + 全局入账序号。 */
    public record StoredTransaction(Transaction transaction, RiskAssessment assessment, long seq) {
    }
}
