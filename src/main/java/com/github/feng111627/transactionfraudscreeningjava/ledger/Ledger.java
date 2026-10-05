package com.github.feng111627.transactionfraudscreeningjava.ledger;

import com.github.feng111627.transactionfraudscreeningjava.model.AuditEvent;
import com.github.feng111627.transactionfraudscreeningjava.model.ListType;
import com.github.feng111627.transactionfraudscreeningjava.model.ReviewRecord;
import com.github.feng111627.transactionfraudscreeningjava.model.RuleConfig;
import com.github.feng111627.transactionfraudscreeningjava.model.TxnRecord;
import com.github.feng111627.transactionfraudscreeningjava.model.WatchEntry;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 内存账本：交易、名单、规则配置版本、复核单与审计事件的统一存储。
 * <p>并发约定：
 * <ul>
 *   <li>{@link #insertTxnIfAbsent} 保证同一 requestId 只落账一次（幂等）；</li>
 *   <li>{@link #evaluateTxn} 以账户为粒度加锁，保证同账户并发判定不漏算/不重复计数；</li>
 *   <li>规则配置按版本保存，历史判定引用当时版本，保证结论可复现。</li>
 * </ul>
 */
public interface Ledger {

    /** 若 requestId 不存在则落账并返回新记录；否则返回既有记录。existing 标记是否为重复提交。 */
    TxnRecord insertTxnIfAbsent(TxnRecord record);

    boolean existsRequestId(String requestId);

    Optional<TxnRecord> findTxnByRequestId(String requestId);

    Optional<TxnRecord> findTxnByTxnId(String txnId);

    List<TxnRecord> allTxns();

    /**
     * 在账户锁内执行一次判定评估：supplier 可安全地统计该账户窗口内的已入账交易，
     * 并把评估结论挂到当前交易上。同一笔交易只会被评估一次。
     */
    TxnRecord evaluateTxn(TxnRecord txn, Supplier<TxnRecord> evaluator);

    /** 追加审计事件（只增不改），返回全局递增序号。 */
    AuditEvent appendAudit(AuditEvent event);

    List<AuditEvent> allAudits();

    /** 当前生效的规则配置。 */
    RuleConfig currentRuleConfig();

    /** 按版本取配置（用于历史结论复现）。 */
    Optional<RuleConfig> ruleConfigAt(long version);

    /** 发布新版本配置（单调递增）。 */
    RuleConfig updateRuleConfig(RuleConfig next);

    /** 名单 upsert（主体存在则切换 active / 更新原因）。 */
    WatchEntry upsertWatch(ListType type, String subject, String reason, String operator, long now);

    List<WatchEntry> allWatchEntries();

    boolean isWatchlisted(ListType type, String subject);

    /** 该收款方是否曾被该账户成功支付（已入账的历史交易，不含当前这笔）。 */
    boolean hasPriorPayment(String accountId, String targetId);

    /** 保存/更新复核单。 */
    void saveReview(ReviewRecord review);

    Optional<ReviewRecord> findReviewByTxnId(String txnId);

    Optional<ReviewRecord> findReviewById(String reviewId);

    List<ReviewRecord> allReviews();

    /** 生成全局唯一 ID。 */
    String nextTxnId();

    String nextReviewId();

    /** 判定处理顺序的全局单调序号（进入账户锁开始判定时分配）。 */
    long nextProcessedSeq();
}
