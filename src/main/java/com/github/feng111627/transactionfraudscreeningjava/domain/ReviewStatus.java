package com.github.feng111627.transactionfraudscreeningjava.domain;

/**
 * 人工复核工单状态。
 */
public enum ReviewStatus {
    OPEN,
    IN_PROGRESS,
    RESOLVED_APPROVE,
    RESOLVED_BLOCK,
    CLOSED
}
