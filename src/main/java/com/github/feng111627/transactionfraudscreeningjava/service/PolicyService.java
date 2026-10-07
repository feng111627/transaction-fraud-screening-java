package com.github.feng111627.transactionfraudscreeningjava.service;

import com.github.feng111627.transactionfraudscreeningjava.domain.PolicyVersion;
import com.github.feng111627.transactionfraudscreeningjava.domain.Role;
import com.github.feng111627.transactionfraudscreeningjava.dto.PolicyPublishRequest;
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
 * 策略版本发布与切换服务。
 *
 * - 每次发布产生一个不可变的新版本（记录发布人、时间、内容）；
 * - 发布即切换：之后新接入的交易按新版本判定，历史结论不被改写；
 * - 只有 POLICY_ADMIN 角色可以发布；越权尝试被拒绝、留痕且无任何副作用；
 * - 版本号唯一：同版本号重复发布视为试图篡改已发布版本，拒绝（409）。
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
        // 服务启动时把引擎内置的默认配置登记为初始版本，保证版本注册表与引擎一致。
        RuleConfig initial = ruleEngine.currentConfig();
        policyStore.publish(new PolicyVersion(initial, "system", Instant.now(), "初始默认策略"));
    }

    /**
     * 发布新策略版本并立即切换为当前生效版本。
     * 整个方法串行执行，并发发布时版本登记与生效指针切换不会交错。
     */
    public synchronized PolicyVersion publish(PolicyPublishRequest request) {
        requirePolicyAdmin(request == null ? null : request.operator(),
                request == null ? null : request.role());

        RuleConfig config = request.config();
        List<String> errors = validateConfig(config);
        if (!errors.isEmpty()) {
            String reason = String.join("; ", errors);
            auditLog.append(request.operator(), "POLICY_PUBLISH_REJECTED",
                    config == null ? "null" : String.valueOf(config.version()), reason);
            throw new ApiException(HttpStatus.BAD_REQUEST, reason);
        }

        PolicyVersion version = new PolicyVersion(config, request.operator(), Instant.now(), request.note());
        if (!policyStore.publish(version)) {
            auditLog.append(request.operator(), "POLICY_PUBLISH_REJECTED", config.version(),
                    "版本已存在，已发布版本不可变");
            throw new ApiException(HttpStatus.CONFLICT,
                    "策略版本 " + config.version() + " 已发布，已发布版本不可修改，请使用新版本号");
        }
        // 切换实时判定到新版本；历史交易的结论已落库，不受影响。
        ruleEngine.updateConfig(config);
        auditLog.append(request.operator(), "POLICY_PUBLISHED", config.version(),
                "publishedBy=" + request.operator()
                        + (request.note() == null ? "" : " note=" + request.note()));
        return version;
    }

    public PolicyVersion active() {
        return policyStore.active();
    }

    public List<PolicyVersion> list() {
        return policyStore.findAll();
    }

    /** 校验当前操作者具备策略管理权限；越权时留痕并抛 403，不产生任何副作用。 */
    void requirePolicyAdmin(String operator, Role role) {
        if (operator == null || operator.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "operator 不能为空");
        }
        if (role != Role.POLICY_ADMIN) {
            auditLog.append(operator, "POLICY_PERMISSION_DENIED", "policy",
                    "角色 " + role + " 无策略管理权限，需要 POLICY_ADMIN");
            throw new ApiException(HttpStatus.FORBIDDEN, "策略管理操作需要 POLICY_ADMIN 角色");
        }
    }

    /** 策略配置合法性校验，发布与预演共用同一口径。 */
    static List<String> validateConfig(RuleConfig config) {
        List<String> errors = new ArrayList<>();
        if (config == null) {
            errors.add("缺少字段: config");
            return errors;
        }
        if (config.version() == null || config.version().isBlank()) {
            errors.add("缺少字段: config.version");
        }
        if (config.highAmountThreshold() == null
                || config.highAmountThreshold().compareTo(BigDecimal.ZERO) <= 0) {
            errors.add("highAmountThreshold 必须为正数");
        }
        if (config.highAmountScore() < 0 || config.velocityScore() < 0 || config.watchlistScore() < 0) {
            errors.add("规则分值不能为负数");
        }
        if (config.velocityWindowSeconds() <= 0) {
            errors.add("velocityWindowSeconds 必须为正数");
        }
        if (config.velocityMaxCount() < 1) {
            errors.add("velocityMaxCount 至少为 1");
        }
        if (config.reviewThreshold() < 0 || config.blockThreshold() < 0) {
            errors.add("判定阈值不能为负数");
        }
        if (config.reviewThreshold() > config.blockThreshold()) {
            errors.add("reviewThreshold 不能大于 blockThreshold");
        }
        return errors;
    }
}
