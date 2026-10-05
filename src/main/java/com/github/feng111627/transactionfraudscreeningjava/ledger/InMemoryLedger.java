package com.github.feng111627.transactionfraudscreeningjava.ledger;

import com.github.feng111627.transactionfraudscreeningjava.model.AuditEvent;
import com.github.feng111627.transactionfraudscreeningjava.model.ListType;
import com.github.feng111627.transactionfraudscreeningjava.model.ReviewRecord;
import com.github.feng111627.transactionfraudscreeningjava.model.RuleConfig;
import com.github.feng111627.transactionfraudscreeningjava.model.TxnRecord;
import com.github.feng111627.transactionfraudscreeningjava.model.WatchEntry;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 基于并发集合 + 账户分段锁的内存账本。
 * <ul>
 *   <li>交易按 requestId 去重，幂等由 {@link ConcurrentHashMap#putIfAbsent} 保证；</li>
 *   <li>同账户的风险判定串行化（per-account 锁），频率计数不会漏算/重算；</li>
 *   <li>规则配置保存全部历史版本，当前版本号单调递增；</li>
 *   <li>审计事件以全局 AtomicLong 序号追加，永不修改/删除。</li>
 * </ul>
 */
@Component
public class InMemoryLedger implements Ledger {


    private final ConcurrentMap<String, TxnRecord> txnsByRequestId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, TxnRecord> txnsByTxnId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ReviewRecord> reviewsByTxnId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ReviewRecord> reviewsById = new ConcurrentHashMap<>();
    private final List<AuditEvent> auditLog = java.util.Collections.synchronizedList(new ArrayList<>());

    /** 每个账户一把锁，串行化同账户判定 */
    private final ConcurrentMap<String, Object> accountLocks = new ConcurrentHashMap<>();

    /** key = type + '\0' + subject */
    private final ConcurrentMap<String, WatchEntry> watchIndex = new ConcurrentHashMap<>();

    /** 配置版本 -> 配置；版本单调递增 */
    private final ConcurrentSkipListMap<Long, RuleConfig> configHistory = new ConcurrentSkipListMap<>();
    private volatile long currentConfigVersion = 0L;

    private final AtomicLong auditSeq = new AtomicLong(0);
    private final AtomicLong txnSeq = new AtomicLong(0);
    private final AtomicLong reviewSeq = new AtomicLong(0);
    private final AtomicLong processedSeq = new AtomicLong(0);

    public InMemoryLedger() {
        // v1 默认口径，详见 README 风险规则与计分口径
        RuleConfig defaults = new RuleConfig(
                1L,
                new BigDecimal("50000"), 30,   // 大额阈值与加分
                300L, 3, 40,                   // 5 分钟窗口内 >3 笔，加 40
                60, 50,                        // 命中账户名单 60，命中收款方名单 50
                15,                            // 首次向新收款方支付加 15
                40, 80);                       // >=40 转复核，>=80 拒绝
        configHistory.put(1L, defaults);
        currentConfigVersion = 1L;
    }

    @Override
    public TxnRecord insertTxnIfAbsent(TxnRecord record) {
        TxnRecord existing = txnsByRequestId.putIfAbsent(record.getRequestId(), record);
        if (existing != null) {
            return existing;
        }
        if (record.getTxnId() != null) {
            txnsByTxnId.put(record.getTxnId(), record);
        }
        return record;
    }

    @Override
    public boolean existsRequestId(String requestId) {
        return txnsByRequestId.containsKey(requestId);
    }

    @Override
    public Optional<TxnRecord> findTxnByRequestId(String requestId) {
        return Optional.ofNullable(txnsByRequestId.get(requestId));
    }

    @Override
    public Optional<TxnRecord> findTxnByTxnId(String txnId) {
        return Optional.ofNullable(txnsByTxnId.get(txnId));
    }

    @Override
    public List<TxnRecord> allTxns() {
        return new ArrayList<>(txnsByRequestId.values());
    }

    @Override
    public TxnRecord evaluateTxn(TxnRecord txn, Supplier<TxnRecord> evaluator) {
        if (txn.getEvaluation() != null) {
            return txn; // 已经判定过，幂等直接返回
        }
        Object lock = accountLocks.computeIfAbsent(txn.getAccountId(), k -> new Object());
        synchronized (lock) {
            if (txn.getEvaluation() != null) {
                return txn; // 双检：同账户并发下只判定一次
            }
            TxnRecord evaluated = evaluator.get();
            return evaluated;
        }
    }

    @Override
    public AuditEvent appendAudit(AuditEvent event) {
        AuditEvent stamped = new AuditEvent(
                auditSeq.incrementAndGet(),
                event.timeEpochMilli(),
                event.action(),
                event.operator(),
                event.targetRef(),
                event.detail(),
                event.result());
        auditLog.add(stamped);
        return stamped;
    }

    @Override
    public List<AuditEvent> allAudits() {
        synchronized (auditLog) {
            return new ArrayList<>(auditLog);
        }
    }

    @Override
    public RuleConfig currentRuleConfig() {
        return configHistory.get(currentConfigVersion);
    }

    @Override
    public Optional<RuleConfig> ruleConfigAt(long version) {
        return Optional.ofNullable(configHistory.get(version));
    }

    @Override
    public RuleConfig updateRuleConfig(RuleConfig next) {
        configHistory.put(next.version(), next);
        currentConfigVersion = next.version();
        return next;
    }

    @Override
    public WatchEntry upsertWatch(ListType type, String subject, String reason,
                                  String operator, long now) {
        WatchEntry entry = new WatchEntry(type, subject, reason, operator, now, true);
        watchIndex.put(watchKey(type, subject), entry);
        return entry;
    }

    @Override
    public List<WatchEntry> allWatchEntries() {
        return watchIndex.values().stream()
                .sorted(Comparator.comparing(WatchEntry::addedAtEpochMilli))
                .toList();
    }

    @Override
    public boolean isWatchlisted(ListType type, String subject) {
        WatchEntry e = watchIndex.get(watchKey(type, subject));
        return e != null && e.active();
    }

    @Override
    public boolean hasPriorPayment(String accountId, String targetId) {
        // 遍历当前已入账交易：同一账户此前是否成功支付过该收款方
        for (TxnRecord r : txnsByRequestId.values()) {
            if (r.getAccountId() != null && r.getAccountId().equals(accountId)
                    && r.getTargetId() != null && r.getTargetId().equals(targetId)
                    && r.getStatus() == com.github.feng111627.transactionfraudscreeningjava.model.TxnStatus.ACCEPTED) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void saveReview(ReviewRecord review) {
        reviewsById.put(review.getReviewId(), review);
        reviewsByTxnId.put(review.getTxnId(), review);
    }

    @Override
    public Optional<ReviewRecord> findReviewByTxnId(String txnId) {
        return Optional.ofNullable(reviewsByTxnId.get(txnId));
    }

    @Override
    public Optional<ReviewRecord> findReviewById(String reviewId) {
        return Optional.ofNullable(reviewsById.get(reviewId));
    }

    @Override
    public List<ReviewRecord> allReviews() {
        return List.copyOf(reviewsById.values());
    }

    @Override
    public String nextTxnId() {
        return "TXN-" + txnSeq.incrementAndGet();
    }

    @Override
    public String nextReviewId() {
        return "REV-" + reviewSeq.incrementAndGet();
    }

    @Override
    public long nextProcessedSeq() {
        return processedSeq.incrementAndGet();
    }

    private static String watchKey(ListType type, String subject) {
        return type.name() + '\0' + subject;
    }
}
