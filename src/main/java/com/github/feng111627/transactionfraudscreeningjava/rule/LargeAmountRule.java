package com.github.feng111627.transactionfraudscreeningjava.rule;

import com.github.feng111627.transactionfraudscreeningjava.model.HitRule;
import com.github.feng111627.transactionfraudscreeningjava.model.RuleCode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * R001 大额交易：金额 ≥ largeAmount 时加 largeAmountScore。
 */
@Component
public class LargeAmountRule implements RiskRule {

    @Override
    public Optional<HitRule> evaluate(RuleContext ctx) {
        BigDecimal amount = ctx.txn().getAmount();
        BigDecimal threshold = ctx.config().largeAmount();
        if (amount != null && amount.compareTo(threshold) >= 0) {
            String detail = String.format("交易金额 %s %s ≥ 大额阈值 %s",
                    amount.toPlainString(), ctx.txn().getCurrency(), threshold.toPlainString());
            return Optional.of(new HitRule(
                    RuleCode.R001_LARGE_AMOUNT,
                    ctx.config().largeAmountScore(),
                    "交易金额达到/超过大额阈值",
                    detail));
        }
        return Optional.empty();
    }

    @Override
    public String code() {
        return RuleCode.R001_LARGE_AMOUNT.name();
    }
}
