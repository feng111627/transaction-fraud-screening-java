package com.github.feng111627.transactionfraudscreeningjava.dto;

import com.github.feng111627.transactionfraudscreeningjava.domain.Role;
import com.github.feng111627.transactionfraudscreeningjava.rules.RuleConfig;

/**
 * 策略版本发布请求。config.version 即新版本号，发布后不可变。
 */
public record PolicyPublishRequest(
        String operator,
        Role role,
        String note,
        RuleConfig config
) {
}
