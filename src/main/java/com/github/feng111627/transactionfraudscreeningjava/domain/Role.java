package com.github.feng111627.transactionfraudscreeningjava.domain;

/**
 * 操作者角色，决定复核与覆盖权限。
 */
public enum Role {
    ANALYST,
    SUPERVISOR,
    AUDITOR,
    /** 策略运营管理员：发布策略版本、影响预演、名单增删。 */
    POLICY_ADMIN
}
