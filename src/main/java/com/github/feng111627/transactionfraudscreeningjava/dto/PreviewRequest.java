package com.github.feng111627.transactionfraudscreeningjava.dto;

import com.github.feng111627.transactionfraudscreeningjava.domain.Role;
import com.github.feng111627.transactionfraudscreeningjava.rules.RuleConfig;

/**
 * 影响预演请求：拿一套尚未发布的候选策略，对库里已有历史交易试算。
 * 候选策略只参与本次计算，不会被发布，也不影响实时判定。
 */
public record PreviewRequest(
        String operator,
        Role role,
        RuleConfig config
) {
}
