package com.github.feng111627.transactionfraudscreeningjava.domain;

import com.github.feng111627.transactionfraudscreeningjava.rules.RuleConfig;

import java.time.Instant;

/**
 * 一个已发布的策略版本。发布后内容不可变：
 * 想调整策略只能以新版本号再发一版。
 * 记录发布人与发布时间，供审计与对账追溯。
 */
public record PolicyVersion(
        RuleConfig config,
        String publishedBy,
        Instant publishedAt,
        String note
) {
    public String version() {
        return config.version();
    }
}
