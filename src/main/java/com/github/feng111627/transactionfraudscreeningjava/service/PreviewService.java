package com.github.feng111627.transactionfraudscreeningjava.service;

import com.github.feng111627.transactionfraudscreeningjava.domain.RiskAssessment;
import com.github.feng111627.transactionfraudscreeningjava.dto.PreviewRequest;
import com.github.feng111627.transactionfraudscreeningjava.dto.PreviewResult;
import com.github.feng111627.transactionfraudscreeningjava.exception.ApiException;
import com.github.feng111627.transactionfraudscreeningjava.rules.RuleConfig;
import com.github.feng111627.transactionfraudscreeningjava.store.TransactionStore;
import com.github.feng111627.transactionfraudscreeningjava.store.WatchlistStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 策略影响预演服务：正式发布前，拿候选策略对库里已有历史交易试算，
 * 评估"如果当时用的是这套策略，会有多少笔结论发生变化"。
 *
 * 预演口径：对每笔历史交易，保持它当初的名单状态与入账次序不变，
 * 只把策略配置换成候选配置重算，再与落库结论比对。
 *
 * 预演是纯只读计算：不落库、不计数、不开工单、不写审计、
 * 不触碰引擎当前生效配置，候选策略在正式发布前对实时判定无任何影响。
 * （越权尝试本身会被拒绝并留痕——那是权限系统的事件，预演并未执行。）
 */
@Service
public class PreviewService {

    /** 预演结果中最多保留的典型变化样本数。 */
    static final int MAX_SAMPLES = 5;

    private final TransactionStore transactionStore;
    private final WatchlistStore watchlistStore;
    private final ReplayService replayService;
    private final PolicyService policyService;

    public PreviewService(TransactionStore transactionStore,
                          WatchlistStore watchlistStore,
                          ReplayService replayService,
                          PolicyService policyService) {
        this.transactionStore = transactionStore;
        this.watchlistStore = watchlistStore;
        this.replayService = replayService;
        this.policyService = policyService;
    }

    public PreviewResult preview(PreviewRequest request) {
        policyService.requirePolicyAdmin(request == null ? null : request.operator(),
                request == null ? null : request.role());
        List<String> errors = PolicyService.validateConfig(request.config());
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, String.join("; ", errors));
        }
        RuleConfig candidate = request.config();

        int changed = 0;
        List<PreviewResult.DecisionChange> samples = new ArrayList<>();
        List<TransactionStore.StoredTransaction> all = transactionStore.findAllOrdered();
        for (TransactionStore.StoredTransaction stored : all) {
            RiskAssessment original = stored.assessment();
            // 保持该交易当初的名单状态不变，只换策略配置
            WatchlistStore.WatchlistSnapshot watchlist =
                    watchlistStore.snapshotAt(original.watchlistVersion()).orElseThrow();
            RiskAssessment trial = replayService.recomputeWith(stored, candidate, watchlist);
            if (trial.decision() != original.decision()) {
                changed++;
                if (samples.size() < MAX_SAMPLES) {
                    samples.add(new PreviewResult.DecisionChange(
                            stored.transaction().transactionId(),
                            original.decision(), trial.decision(),
                            original.totalScore(), trial.totalScore()));
                }
            }
        }
        return new PreviewResult(candidate.version(), all.size(), changed,
                all.size() - changed, List.copyOf(samples));
    }
}
