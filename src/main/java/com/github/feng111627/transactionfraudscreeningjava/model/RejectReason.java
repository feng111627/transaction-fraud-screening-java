package com.github.feng111627.transactionfraudscreeningjava.model;

/** 交易被拒绝（不落业务计数）的明确原因 */
public enum RejectReason {
    REQUEST_ID_BLANK,
    ACCOUNT_ID_BLANK,
    TARGET_ID_BLANK,
    AMOUNT_NULL,
    AMOUNT_NOT_POSITIVE,
    CURRENCY_BLANK,
    TXN_TIME_NULL,
    TXN_TIME_IN_FUTURE,
    TXN_TIME_TOO_OLD,
    IDEMPOTENCY_MISMATCH
}
