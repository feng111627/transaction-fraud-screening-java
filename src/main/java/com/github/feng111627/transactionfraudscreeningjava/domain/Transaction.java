package com.github.feng111627.transactionfraudscreeningjava.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 一笔待筛查的交易。字段在接入时完成校验后才允许构造。
 */
public record Transaction(
        String transactionId,
        String accountId,
        BigDecimal amount,
        String currency,
        String merchantId,
        String channel,
        Instant occurredAt
) {
}
