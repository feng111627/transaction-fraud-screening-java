package com.github.feng111627.transactionfraudscreeningjava.rule;

import com.github.feng111627.transactionfraudscreeningjava.model.HitRule;
import com.github.feng111627.transactionfraudscreeningjava.model.RuleCode;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * R002 滑动窗口频率：以系统处理时刻（FIFO）计，同一账户在本笔交易之前的
 * frequencyWindowSeconds 滑动窗口内，已被系统接受并完成判定的交易笔数
 * 达到 frequencyLimit 时命中并加分。
 * 窗口内笔数由引擎在账户锁内统计后放入上下文，只包含处理顺序更早的交易，
 * 保证并发下不漏算/不重复计数，且与线程调度顺序无关。
 */
@Component
public class FrequencyWindowRule implements RiskRule {

    @Override
    public Optional<HitRule> evaluate(RuleContext ctx) {
        int count = ctx.priorTxnsInWindow().size();
        int limit = ctx.config().frequencyLimit();
        if (count >= limit) {
            long seconds = ctx.config().frequencyWindowSeconds();
            String detail = String.format(
                    "账户 %s 在本笔交易之前的 %d 秒滑动窗口内已有 %d 笔已处理交易，达到阈值 %d 笔",
                    ctx.txn().getAccountId(), seconds, count, limit);
            return Optional.of(new HitRule(
                    RuleCode.R002_FREQUENCY_WINDOW,
                    ctx.config().frequencyScore(),
                    "时间窗口内交易频率达到阈值",
                    detail));
        }
        return Optional.empty();
    }

    @Override
    public String code() {
        return RuleCode.R002_FREQUENCY_WINDOW.name();
    }
}
