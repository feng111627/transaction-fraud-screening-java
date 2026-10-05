package com.github.feng111627.transactionfraudscreeningjava.store;

import com.github.feng111627.transactionfraudscreeningjava.domain.RiskAssessment;
import com.github.feng111627.transactionfraudscreeningjava.domain.Transaction;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 内存交易账本：负责幂等落库、按账户的时间窗口统计。
 *
 * 并发约定：
 * - 同一 transactionId 通过双重检查 + 账户级锁保证只入账一次；
 * - 同一账户的"窗口计数 + 评估 + 入账"在账户级锁内串行完成，
 *   并发交易不会漏算或重复计算窗口频率。
 */
@Repository
public class TransactionStore {

    private final ConcurrentHashMap<String, StoredTransaction> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> accountLocks = new ConcurrentHashMap<>();

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
            StoredTransaction created = new StoredTransaction(tx, assessment);
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
            return byId.values().stream()
                    .map(StoredTransaction::transaction)
                    .filter(t -> t.accountId().equals(accountId))
                    .filter(t -> !t.occurredAt().isBefore(from) && !t.occurredAt().isAfter(to))
                    .count();
        }
    }

    public List<StoredTransaction> findAll() {
        return List.copyOf(byId.values());
    }

    /** 落库记录：交易 + 其风险结论。 */
    public record StoredTransaction(Transaction transaction, RiskAssessment assessment) {
    }
}
