package com.github.feng111627.transactionfraudscreeningjava.domain;

import com.github.feng111627.transactionfraudscreeningjava.rules.RuleConfig;

import java.time.Instant;

/**
 * 一个已发布的策略版本：内容不可变，记录发布人与发布时间。
 * 发布后不可修改，任何调整只能发布新版本。
 */
public record PolicyVersion(
        String version,
        RuleConfig config,
        String publishedBy,
        Instant publishedAt
) {
}
