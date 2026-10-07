package com.github.feng111627.transactionfraudscreeningjava.dto;

import com.github.feng111627.transactionfraudscreeningjava.domain.Role;

/**
 * 名单增删请求。增删名单属于策略运营操作，需要 POLICY_ADMIN 角色。
 */
public record WatchlistRequest(
        String operator,
        Role role,
        String reason
) {
}
