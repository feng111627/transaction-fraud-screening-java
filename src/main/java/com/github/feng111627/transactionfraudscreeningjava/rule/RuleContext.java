package com.github.feng111627.transactionfraudscreeningjava.rule;

import com.github.feng111627.transactionfraudscreeningjava.model.RuleConfig;
import com.github.feng111627.transactionfraudscreeningjava.model.TxnRecord;

import java.util.List;

/**
 * 规则评估上下文。所有规则需要的数据（交易、窗口内历史交易、名单命中、历史关系）
 * 都在账户锁内一次性算好，规则本身不读取"当前时间/当前配置"，
 * 从而保证历史结论可用相同输入复现。
 */
public record RuleContext(
        TxnRecord txn,
        List<TxnRecord> priorTxnsInWindow,
        boolean accountWatchlisted,
        boolean targetWatchlisted,
        boolean hasPriorPayment,
        RuleConfig config
) {
}
