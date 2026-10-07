package com.github.feng111627.transactionfraudscreeningjava.rules;

import com.github.feng111627.transactionfraudscreeningjava.domain.Decision;
import com.github.feng111627.transactionfraudscreeningjava.domain.RiskAssessment;
import com.github.feng111627.transactionfraudscreeningjava.domain.RuleHit;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 规则引擎：用当前生效的 RuleConfig 评估上下文并给出结论。
 *
 * 计分口径：总分 = 各命中规则分值之和；
 * totalScore >= blockThreshold -> BLOCK；
 * totalScore >= reviewThreshold -> REVIEW；
 * 否则 APPROVE。
 *
 * 判定结果记录规则集版本与名单版本，配置或名单更新后，
 * 历史结论仍可按当时版本复现。
 */
@Service
public class RiskRuleEngine {

    private final List<RiskRule> rules;
    private volatile RuleConfig config = RuleConfig.defaultConfig();

    public RiskRuleEngine(List<RiskRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public RiskAssessment assess(RuleContext context, long watchlistVersion) {
        return assess(context, config, watchlistVersion);
    }

    /**
     * 用调用方给定的配置快照评估：一次判定从头到尾使用同一份配置，
     * 发布新版本不会把新旧规则掺进同一笔交易的判定里。
     */
    public RiskAssessment assess(RuleContext context, RuleConfig snapshot, long watchlistVersion) {
        List<RuleHit> hits = rules.stream()
                .map(rule -> rule.evaluate(context, snapshot))
                .flatMap(Optional::stream)
                .toList();
        int totalScore = hits.stream().mapToInt(RuleHit::score).sum();
        Decision decision = decide(totalScore, snapshot);
        String versionTag = "rules:" + snapshot.version() + "/watchlist:v" + watchlistVersion;
        return new RiskAssessment(
                context.transaction().transactionId(),
                totalScore,
                decision,
                hits,
                versionTag,
                Instant.now(),
                snapshot.version(),
                watchlistVersion,
                context.recentCount(),
                context.watchlistEntry());
    }

    private Decision decide(int totalScore, RuleConfig cfg) {
        if (totalScore >= cfg.blockThreshold()) {
            return Decision.BLOCK;
        }
        if (totalScore >= cfg.reviewThreshold()) {
            return Decision.REVIEW;
        }
        return Decision.APPROVE;
    }

    public RuleConfig currentConfig() {
        return config;
    }

    /** 更新规则配置（应使用新版本号）。 */
    public void updateConfig(RuleConfig config) {
        this.config = config;
    }
}
