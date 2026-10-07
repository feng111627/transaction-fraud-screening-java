package com.github.feng111627.transactionfraudscreeningjava.domain;

/**
 * 操作者角色，决定复核与覆盖权限。
 *
 * POLICY_ADMIN：策略管理角色，可发布策略版本、维护名单、做影响预演；
 * 不参与人工复核流转。
 */
public enum Role {
    ANALYST,
    SUPERVISOR,
    AUDITOR,
    POLICY_ADMIN
}
