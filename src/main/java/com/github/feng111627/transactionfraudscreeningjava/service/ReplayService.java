package com.github.feng111627.transactionfraudscreeningjava.service;

import com.github.feng111627.transactionfraudscreeningjava.domain.PolicyVersion;
import com.github.feng111627.transactionfraudscreeningjava.domain.RiskAssessment;
import com.github.feng111627.transactionfraudscreeningjava.domain.RuleHit;
import com.github.feng111627.transactionfraudscreeningjava.dto.ReplayRequest;
import com.github.feng111627.transactionfraudscreeningjava.dto.ReplayResult;
import com.github.feng111627.transactionfraudscreeningjava.exception.ApiException;
import com.github.feng111627.transactionfraudscreeningjava.rules.RiskRuleEngine;
import com.github.feng111627.transactionfraudscreeningjava.rules.RuleContext;
import com.github.feng111627.transactionfraudscreeningjava.store.AuditLog;
import com.github.feng111627.transactionfraudscreeningjava.store.PolicyStore;
import com.github.feng111627.transactionfraudscreeningjava.store.TransactionStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 历史交易重算服务：按交易当时生效的策略版本与当时的判定输入
 * （窗口计数、名单命中快照）重新计算，与落库结论逐项比对。
 *
 * 重算是只读核对：不新增交易计数、不重复开工单、不覆盖原结论；
 * 每次重算的操作者、时间、是否一致都会写入审计。
 */
@Service
public class ReplayService {

    private final TransactionStore transactionStore;
    private final PolicyStore policyStore;
    private final RiskRuleEngine ruleEngine;
    private final PolicyService policyService;
    private final AuditLog auditLog;

    public ReplayService(TransactionStore transactionStore,
                         PolicyStore policyStore,
                         RiskRuleEngine ruleEngine,
                         PolicyService policyService,
                         AuditLog auditLog) {
        this.transactionStore = transactionStore;
        this.policyStore = policyStore;
        this.ruleEngine = ruleEngine;
        this.policyService = policyService;
        this.auditLog = auditLog;
    }

    public ReplayResult replay(String transactionId, ReplayRequest request) {
        policyService.requireOperator(
                request == null ? null : request.operator(),
                request == null ? null : request.role());

        TransactionStore.StoredTransaction stored = transactionStore.findById(transactionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "交易不存在: " + transactionId));
        RiskAssessment original = stored.assessment();

        PolicyVersion policy = policyStore.find(original.policyVersion())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                        "交易当时的策略版本已不存在: " + original.policyVersion()));

        // 严格使用当时的判定输入：策略版本内容 + 窗口计数 + 名单命中快照
        RuleContext context = new RuleContext(stored.transaction(),
                original.recentCount(), original.watchlistEntry());
        RiskAssessment recomputed = ruleEngine.assess(context, policy.config(), original.watchlistVersion());

        boolean consistent = recomputed.totalScore() == original.totalScore()
                && recomputed.decision() == original.decision()
                && recomputed.hits().equals(original.hits());

        auditLog.append(request.operator(), "TRANSACTION_REPLAY", transactionId,
                "policy=" + original.policyVersion()
                        + " watchlist=v" + original.watchlistVersion()
                        + " consistent=" + consistent
                        + " score=" + original.totalScore() + "->" + recomputed.totalScore()
                        + " decision=" + original.decision() + "->" + recomputed.decision());

        return new ReplayResult(
                transactionId,
                original.policyVersion(),
                original.watchlistVersion(),
                original.totalScore(),
                original.decision(),
                original.hits().stream().map(RuleHit::ruleId).toList(),
                recomputed.totalScore(),
                recomputed.decision(),
                recomputed.hits().stream().map(RuleHit::ruleId).toList(),
                consistent,
                request.operator(),
                Instant.now());
    }
}
