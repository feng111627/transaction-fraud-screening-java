package com.github.feng111627.transactionfraudscreeningjava.rules;

import com.github.feng111627.transactionfraudscreeningjava.domain.RuleHit;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 频率规则：velocityWindowSeconds 秒内同账户已入账交易笔数（不含当前笔）
 * 达到 velocityMaxCount 时加 velocityScore 分。
 */
@Component
public class VelocityRule implements RiskRule {

    @Override
    public String id() {
        return "VELOCITY_HIGH";
    }

    @Override
    public Optional<RuleHit> evaluate(RuleContext context, RuleConfig config) {
        if (context.recentCount() >= config.velocityMaxCount()) {
            return Optional.of(new RuleHit(
                    id(),
                    "短时高频交易",
                    config.velocityScore(),
                    "账户 " + context.transaction().accountId() + " 在 "
                            + config.velocityWindowSeconds() + " 秒窗口内已有 "
                            + context.recentCount() + " 笔交易，达到上限 " + config.velocityMaxCount()));
        }
        return Optional.empty();
    }
}
