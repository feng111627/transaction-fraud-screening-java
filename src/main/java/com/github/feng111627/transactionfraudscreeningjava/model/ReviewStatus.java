package com.github.feng111627.transactionfraudscreeningjava.model;

/** 人工复核单状态机：PENDING -> INVESTIGATING -> RESOLVED */
public enum ReviewStatus {
    PENDING,
    INVESTIGATING,
    RESOLVED
}
