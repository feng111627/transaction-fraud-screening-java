package com.github.feng111627.transactionfraudscreeningjava.store;

import com.github.feng111627.transactionfraudscreeningjava.domain.PolicyVersion;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 策略版本注册表：保存全部已发布版本与当前生效指针。
 * 版本号唯一，putIfAbsent 保证已发布版本不会被覆盖（不可变）。
 */
@Repository
public class PolicyStore {

    private final ConcurrentHashMap<String, PolicyVersion> byVersion = new ConcurrentHashMap<>();
    private volatile PolicyVersion active;

    /** 登记新版本并切换为当前生效。版本号已存在时返回 false，不做任何改动。 */
    public synchronized boolean publish(PolicyVersion version) {
        if (byVersion.putIfAbsent(version.version(), version) != null) {
            return false;
        }
        active = version;
        return true;
    }

    public PolicyVersion active() {
        return active;
    }

    public Optional<PolicyVersion> find(String version) {
        return Optional.ofNullable(byVersion.get(version));
    }

    public List<PolicyVersion> findAll() {
        return byVersion.values().stream()
                .sorted(Comparator.comparing(PolicyVersion::publishedAt))
                .toList();
    }
}
