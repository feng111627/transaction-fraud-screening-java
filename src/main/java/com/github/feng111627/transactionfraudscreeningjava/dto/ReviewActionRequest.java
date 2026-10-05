package com.github.feng111627.transactionfraudscreeningjava.dto;

import com.github.feng111627.transactionfraudscreeningjava.domain.ReviewStatus;
import com.github.feng111627.transactionfraudscreeningjava.domain.Role;

/**
 * 复核操作请求：由谁、以什么角色、把工单迁移到什么状态。
 */
public record ReviewActionRequest(
        String operator,
        Role role,
        ReviewStatus targetStatus,
        String note
) {
}
