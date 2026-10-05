package com.github.feng111627.transactionfraudscreeningjava.model;

/** 审计动作 */
public enum AuditAction {
    TXN_SUBMIT_SINGLE,
    TXN_SUBMIT_BATCH,
    TXN_REJECTED,
    RISK_EVALUATED,
    REVIEW_CREATED,
    REVIEW_CLAIM,
    REVIEW_RESOLVE,
    REVIEW_OVERRIDE,
    ACCESS_DENIED,
    RULE_CONFIG_UPDATED,
    WATCHLIST_UPDATED,
    REPRODUCED
}
