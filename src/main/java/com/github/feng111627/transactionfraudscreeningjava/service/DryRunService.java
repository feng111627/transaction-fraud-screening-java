package com.github.feng111627.transactionfraudscreeningjava.service;

import com.github.feng111627.transactionfraudscreeningjava.domain.RiskAssessment;
import com.github.feng111627.transactionfraudscreeningjava.dto.DryRunResult;
import com.github.feng111627.transactionfraudscreeningjava.dto.PolicySpecRequest;
import com.github.feng111627.transactionfraudscreeningjava.rules.RiskRuleEngine;
import com.github.feng111627.transactionfraudscreeningjava.rules.RuleConfig;
import com.github.feng111627.transactionfraudscreeningjava.rules.RuleContext;
import com.github.feng111627.transactionfraudscreeningjava.store.AuditLog;
import com.github.feng111627.transactionfraudscreeningjava.store.TransactionStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 策略影响预演服务：用候选策略对库内历史交易重算，评估上线影响。
 *
 * 口径：固定每笔交易当时的判定输入（窗口计数、名单命中快照），
 * 只替换策略内容，比较处置结论是否变化。
 *
 * 预演严格只读：不落库、不计数、不开工单、不切换生效策略，
 * 候选版本不会进入策略注册表，对实时判定没有任何影响。
 */
@Service
public class DryRunService {

    private static final int MAX_SAMPLES = 10;

    private final TransactionStore transactionStore;
    private final RiskRuleEngine ruleEngine;
    private final PolicyService policyService;
    private final AuditLog auditLog;

    public DryRunService(TransactionStore transactionStore,
                         RiskRuleEngine ruleEngine,
                         PolicyService policyService,
                         AuditLog auditLog) {
        this.transactionStore = transactionStore;
        this.ruleEngine = ruleEngine;
        this.policyService = policyService;
        this.auditLog = auditLog;
    }

    public DryRunResult dryRun(PolicySpecRequest request) {
        policyService.requireOperator(
                request == null ? null : request.operator(),
                request == null ? null : request.role());
        // 候选策略只做计算，不发布、不注册
        RuleConfig candidate = policyService.toConfig(request);

        int changed = 0;
        int unchanged = 0;
        List<DryRunResult.DecisionChange> samples = new ArrayList<>();
        for (TransactionStore.StoredTransaction stored : transactionStore.findAll()) {
            RiskAssessment original = stored.assessment();
            RuleContext context = new RuleContext(stored.transaction(),
                    original.recentCount(), original.watchlistEntry());
            RiskAssessment projected = ruleEngine.assess(context, candidate, original.watchlistVersion());
            if (projected.decision() != original.decision()) {
                changed++;
                if (samples.size() < MAX_SAMPLES) {
                    samples.add(new DryRunResult.DecisionChange(
                            original.transactionId(),
                            original.totalScore(), original.decision(),
                            projected.totalScore(), projected.decision()));
                }
            } else {
                unchanged++;
            }
        }

        auditLog.append(request.operator(), "POLICY_DRY_RUN", "policy:" + request.version(),
                "只读预演 total=" + (changed + unchanged)
                        + " changed=" + changed + " unchanged=" + unchanged);

        return new DryRunResult(
                request.version(),
                changed + unchanged,
                changed,
                unchanged,
                samples,
                request.operator(),
                Instant.now());
    }
}
