package com.github.feng111627.transactionfraudscreeningjava.store;

import com.github.feng111627.transactionfraudscreeningjava.domain.PolicyVersion;
import com.github.feng111627.transactionfraudscreeningjava.rules.RuleConfig;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 策略版本注册表：保存全部已发布版本（不可变）与当前生效版本指针。
 *
 * - 版本号唯一，重复发布被拒绝；
 * - 生效指针的原子切换保证：一笔交易要么完整按旧版本、要么完整按新版本判定；
 * - 历史版本永不删除，供历史交易按当时版本重算。
 */
@Repository
public class PolicyStore {

    private final Map<String, PolicyVersion> versions = new LinkedHashMap<>();
    private volatile PolicyVersion active;

    public PolicyStore() {
        // 内置初始版本，与 RiskRuleEngine 的默认配置一致
        this.active = new PolicyVersion("v1", RuleConfig.defaultConfig(), "system", Instant.now());
        this.versions.put("v1", this.active);
    }

    /** 发布新版本并切换为生效版本；版本号已存在时返回 false，不产生任何改动。 */
    public synchronized boolean publish(PolicyVersion candidate) {
        if (versions.containsKey(candidate.version())) {
            return false;
        }
        versions.put(candidate.version(), candidate);
        active = candidate;
        return true;
    }

    /** 当前生效版本（单次读取即为一个一致快照）。 */
    public PolicyVersion active() {
        return active;
    }

    public Optional<PolicyVersion> find(String version) {
        return Optional.ofNullable(versions.get(version));
    }

    public synchronized List<PolicyVersion> all() {
        return new ArrayList<>(versions.values());
    }
}
