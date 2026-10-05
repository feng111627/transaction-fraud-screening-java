package com.github.feng111627.transactionfraudscreeningjava.rule;

import com.github.feng111627.transactionfraudscreeningjava.ledger.Ledger;
import com.github.feng111627.transactionfraudscreeningjava.model.Disposition;
import com.github.feng111627.transactionfraudscreeningjava.model.HitRule;
import com.github.feng111627.transactionfraudscreeningjava.model.ListType;
import com.github.feng111627.transactionfraudscreeningjava.model.RiskEvaluation;
import com.github.feng111627.transactionfraudscreeningjava.model.RuleCode;
import com.github.feng111627.transactionfraudscreeningjava.model.RuleConfig;
import com.github.feng111627.transactionfraudscreeningjava.model.TxnRecord;
import com.github.feng111627.transactionfraudscreeningjava.model.TxnStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 风险引擎：在账户锁内构造 {@link RuleContext}，顺序执行全部规则，
 * 汇总命中加分得到总分，并按阈值给出处置结论。
 * <ul>
 *   <li>总分 ≥ rejectThreshold：REJECT；</li>
 *   <li>总分 ≥ reviewThreshold：REVIEW；</li>
 *   <li>否则 APPROVE。</li>
 * </ul>
 * 评估结果记录当时的 configVersion，复现时加载同一版本配置与交易时点的窗口数据，
 * 因此名单/阈值更新后历史结论仍可复现（见 README「复现口径」）。
 */
@Component
public class RiskEngine {

    private final Ledger ledger;
    private final List<RiskRule> rules;

    public RiskEngine(Ledger ledger,
                      LargeAmountRule r001,
                      FrequencyWindowRule r002,
                      WatchAccountRule r003,
                      WatchTargetRule r004,
                      NewPayeeRule r005) {
        this.ledger = ledger;
        this.rules = List.of(r001, r002, r003, r004, r005);
    }

    /** 用当前生效配置对一笔新入账交易做判定（必须在 ledger.evaluateTxn 的账户锁内调用）。 */
    public RiskEvaluation evaluateFresh(TxnRecord txn, long nowMillis) {
        return doEvaluate(txn, ledger.currentRuleConfig(), nowMillis);
    }

    /**
     * 复现一笔历史交易的判定：加载当时的配置版本，并重放该交易时点之前的窗口数据。
     * 名单匹配使用当前名单状态（内存实现未保留名单历史版本，详见 README）。
     */
    public RiskEvaluation reproduce(TxnRecord txn) {
        RiskEvaluation original = txn.getEvaluation();
        long version = original != null ? original.configVersion() : ledger.currentRuleConfig().version();
        RuleConfig cfg = ledger.ruleConfigAt(version).orElseThrow(
                () -> new IllegalStateException("历史规则版本缺失: " + version));
        return doEvaluate(txn, cfg, original != null ? original.evaluatedAtEpochMilli() : 0L);
    }

    private RiskEvaluation doEvaluate(TxnRecord txn, RuleConfig config, long nowMillis) {
        List<TxnRecord> window = collectPriorInWindow(txn, config);
        RuleContext ctx = new RuleContext(
                txn,
                window,
                ledger.isWatchlisted(ListType.ACCOUNT, txn.getAccountId()),
                ledger.isWatchlisted(ListType.TARGET, txn.getTargetId()),
                hasPriorPaymentInProcessingOrder(txn),
                config);

        List<HitRule> hits = new ArrayList<>();
        for (RiskRule rule : rules) {
            Optional<HitRule> hit = rule.evaluate(ctx);
            hit.ifPresent(hits::add);
        }

        int total = hits.stream().mapToInt(HitRule::score).sum();
        Disposition disposition = decide(total, config);

        return new RiskEvaluation(
                txn.getTxnId(),
                total,
                disposition,
                List.copyOf(hits),
                config.version(),
                nowMillis);
    }

    /**
     * 统计"本笔之前、滑动窗口内"系统已处理的同账户已接受交易。
     * <p>实时风控口径（FIFO），以账户锁内分配的处理序号 processedSeq 为唯一排序依据，
     * 不使用墙钟时间（并发下墙钟毫秒与锁获取顺序不保证单调，会造成结论随调度抖动）：
     * <ol>
     *   <li>先按处理序号取本笔之前同账户最近 {@code limit-1} 笔（超过它们，本笔必然超限）；</li>
     *   <li>再用墙钟时间过滤出处理时刻落在 frequencyWindowSeconds 秒窗口内的部分。</li>
     * </ol>
     * 同批并发到达（彼此毫秒级）全部落在同一时间窗口内，结果为确定值，与线程调度无关、可复现。
     */
    private List<TxnRecord> collectPriorInWindow(TxnRecord current, RuleConfig config) {
        long windowMillis = config.frequencyWindowSeconds() * 1000L;
        List<TxnRecord> window = new ArrayList<>();
        for (TxnRecord r : ledger.allTxns()) {
            if (r == current || r.getStatus() != TxnStatus.ACCEPTED || r.getTxnId() == null) {
                continue;
            }
            // 处理顺序更早（账户锁内分配的严格序号），且处理时刻落在滑动窗口内。
            // 序号与处理时刻都在账户锁内确定，二者单调一致，结论与线程调度无关。
            if (current.getAccountId().equals(r.getAccountId())
                    && r.getProcessedSeq() >= 0 && r.getProcessedSeq() < current.getProcessedSeq()
                    && current.getProcessedAtMillis() - r.getProcessedAtMillis() <= windowMillis) {
                window.add(r);
            }
        }
        window.sort(java.util.Comparator.comparingLong(TxnRecord::getProcessedSeq));
        return window;
    }

    /**
     * 是否已有处理顺序更早的同账户-同收款方成功交易。
     * 与频率规则一致，严格以账户锁内分配的 processedSeq 为准：
     * 同账户判定串行，因此同一收款方的第一批并发交易中只有处理序号最小的一笔算"新收款方"，
     * 结论确定且可复现。
     */
    private boolean hasPriorPaymentInProcessingOrder(TxnRecord current) {
        for (TxnRecord r : ledger.allTxns()) {
            if (r == current || r.getStatus() != TxnStatus.ACCEPTED || r.getTxnId() == null) {
                continue;
            }
            if (current.getAccountId().equals(r.getAccountId())
                    && current.getTargetId().equals(r.getTargetId())
                    && r.getProcessedSeq() >= 0
                    && r.getProcessedSeq() < current.getProcessedSeq()) {
                return true;
            }
        }
        return false;
    }

    private Disposition decide(int score, RuleConfig config) {
        if (score >= config.rejectThreshold()) {
            return Disposition.REJECT;
        }
        if (score >= config.reviewThreshold()) {
            return Disposition.REVIEW;
        }
        return Disposition.APPROVE;
    }

    public List<RuleCode> ruleCodes() {
        return rules.stream().map(r -> RuleCode.valueOf(r.code())).toList();
    }
}
