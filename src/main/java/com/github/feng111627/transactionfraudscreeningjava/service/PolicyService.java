package com.github.feng111627.transactionfraudscreeningjava.service;

import com.github.feng111627.transactionfraudscreeningjava.domain.PolicyVersion;
import com.github.feng111627.transactionfraudscreeningjava.domain.Role;
import com.github.feng111627.transactionfraudscreeningjava.dto.PolicySpecRequest;
import com.github.feng111627.transactionfraudscreeningjava.exception.ApiException;
import com.github.feng111627.transactionfraudscreeningjava.rules.RiskRuleEngine;
import com.github.feng111627.transactionfraudscreeningjava.rules.RuleConfig;
import com.github.feng111627.transactionfraudscreeningjava.store.AuditLog;
import com.github.feng111627.transactionfraudscreeningjava.store.PolicyStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 策略运营服务：发布不可变的策略版本并切换生效版本。
 *
 * - 仅 POLICY_ADMIN 可发布；越权尝试拒绝（403）并留痕，不产生任何改动；
 * - 版本号唯一，重复发布拒绝（409）；内容非法拒绝（400），均留痕；
 * - 发布成功即对后续新交易生效，历史结论不被改写；
 * - 发布与生效指针切换在同一临界区完成，并发交易要么完整按旧版本、
 *   要么完整按新版本判定。
 */
@Service
public class PolicyService {

    private final PolicyStore policyStore;
    private final RiskRuleEngine ruleEngine;
    private final AuditLog auditLog;

    public PolicyService(PolicyStore policyStore, RiskRuleEngine ruleEngine, AuditLog auditLog) {
        this.policyStore = policyStore;
        this.ruleEngine = ruleEngine;
        this.auditLog = auditLog;
    }

    /** 发布新版本：校验 -> 权限 -> 落注册表 -> 切换生效配置，全程留痕。 */
    public synchronized PolicyVersion publish(PolicySpecRequest request) {
        requireOperator(request == null ? null : request.operator(), request == null ? null : request.role());
        if (request.role() != Role.POLICY_ADMIN) {
            auditLog.append(request.operator(), "PERMISSION_DENIED", "policy:" + request.version(),
                    "发布策略版本仅 POLICY_ADMIN 可执行，当前角色=" + request.role());
            throw new ApiException(HttpStatus.FORBIDDEN, "发布策略版本仅 POLICY_ADMIN 可执行");
        }
        RuleConfig config = toConfig(request);
        PolicyVersion candidate = new PolicyVersion(
                request.version(), config, request.operator(), Instant.now());
        if (!policyStore.publish(candidate)) {
            auditLog.append(request.operator(), "POLICY_PUBLISH_REJECTED", "policy:" + request.version(),
                    "版本号已存在，策略版本发布后不可修改");
            throw new ApiException(HttpStatus.CONFLICT,
                    "策略版本已存在: " + request.version() + "（已发布版本不可修改，请使用新版本号）");
        }
        // 切换实时判定的生效配置；历史结论仍记录各自版本，不受影响
        ruleEngine.updateConfig(config);
        auditLog.append(request.operator(), "POLICY_PUBLISHED", "policy:" + request.version(),
                "发布人=" + request.operator() + " 内容=" + config);
        return candidate;
    }

    public List<PolicyVersion> list() {
        return policyStore.all();
    }

    public PolicyVersion active() {
        return policyStore.active();
    }

    /**
     * 把请求内容转换为 RuleConfig 并做完整性/合法性校验。
     * 发布与预演共用同一套校验口径；校验失败抛 400，调用方负责留痕。
     */
    public RuleConfig toConfig(PolicySpecRequest r) {
        List<String> errors = new ArrayList<>();
        if (r == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "请求体为空");
        }
        if (isBlank(r.version())) {
            errors.add("缺少字段: version");
        }
        if (r.highAmountThreshold() == null) {
            errors.add("缺少字段: highAmountThreshold");
        } else if (r.highAmountThreshold().compareTo(BigDecimal.ZERO) <= 0) {
            errors.add("非法参数: highAmountThreshold 必须为正数");
        }
        if (r.highAmountScore() == null) {
            errors.add("缺少字段: highAmountScore");
        } else if (r.highAmountScore() < 0) {
            errors.add("非法参数: highAmountScore 不能为负");
        }
        if (r.velocityWindowSeconds() == null) {
            errors.add("缺少字段: velocityWindowSeconds");
        } else if (r.velocityWindowSeconds() <= 0) {
            errors.add("非法参数: velocityWindowSeconds 必须为正数");
        }
        if (r.velocityMaxCount() == null) {
            errors.add("缺少字段: velocityMaxCount");
        } else if (r.velocityMaxCount() <= 0) {
            errors.add("非法参数: velocityMaxCount 必须为正数");
        }
        if (r.velocityScore() == null) {
            errors.add("缺少字段: velocityScore");
        } else if (r.velocityScore() < 0) {
            errors.add("非法参数: velocityScore 不能为负");
        }
        if (r.watchlistScore() == null) {
            errors.add("缺少字段: watchlistScore");
        } else if (r.watchlistScore() < 0) {
            errors.add("非法参数: watchlistScore 不能为负");
        }
        if (r.reviewThreshold() == null) {
            errors.add("缺少字段: reviewThreshold");
        } else if (r.reviewThreshold() < 0) {
            errors.add("非法参数: reviewThreshold 不能为负");
        }
        if (r.blockThreshold() == null) {
            errors.add("缺少字段: blockThreshold");
        } else if (r.blockThreshold() <= 0) {
            errors.add("非法参数: blockThreshold 必须为正数");
        }
        if (errors.isEmpty() && r.reviewThreshold() > r.blockThreshold()) {
            errors.add("非法参数: reviewThreshold 不能大于 blockThreshold");
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, String.join("; ", errors));
        }
        return new RuleConfig(r.version(), r.highAmountThreshold(), r.highAmountScore(),
                r.velocityWindowSeconds(), r.velocityMaxCount(), r.velocityScore(),
                r.watchlistScore(), r.reviewThreshold(), r.blockThreshold());
    }

    /** 操作者与角色必填校验（发布/预演/重算共用）。 */
    public void requireOperator(String operator, Role role) {
        if (isBlank(operator) || role == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "operator/role 均不能为空");
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
