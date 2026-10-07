package com.github.feng111627.transactionfraudscreeningjava.dto;

import com.github.feng111627.transactionfraudscreeningjava.domain.Role;

/**
 * 历史交易重算请求：只读核对，不产生任何业务副作用。
 */
public record ReplayRequest(
        String operator,
        Role role
) {
}
