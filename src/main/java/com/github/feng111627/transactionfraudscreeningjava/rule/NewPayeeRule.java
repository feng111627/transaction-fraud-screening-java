package com.github.feng111627.transactionfraudscreeningjava.rule;

import com.github.feng111627.transactionfraudscreeningjava.model.HitRule;
import com.github.feng111627.transactionfraudscreeningjava.model.RuleCode;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** R005 新收款方：该账户此前没有向此收款方成功支付过，加 newPayeeScore。 */
@Component
public class NewPayeeRule implements RiskRule {

    @Override
    public Optional<HitRule> evaluate(RuleContext ctx) {
        if (!ctx.hasPriorPayment()) {
            String detail = String.format("账户 %s 首次向收款方 %s 付款（无历史成功交易）",
                    ctx.txn().getAccountId(), ctx.txn().getTargetId());
            return Optional.of(new HitRule(
                    RuleCode.R005_NEW_PAYEE_FIRST_TIME,
                    ctx.config().newPayeeScore(),
                    "首次向新收款方付款",
                    detail));
        }
        return Optional.empty();
    }

    @Override
    public String code() {
        return RuleCode.R005_NEW_PAYEE_FIRST_TIME.name();
    }
}
