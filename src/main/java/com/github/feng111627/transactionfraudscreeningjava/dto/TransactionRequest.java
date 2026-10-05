package com.github.feng111627.transactionfraudscreeningjava.dto;

import java.math.BigDecimal;

/**
 * 交易接入请求（单笔）。批量提交为 List<TransactionRequest>。
 */
public record TransactionRequest(
        String transactionId,
        String accountId,
        BigDecimal amount,
        String currency,
        String merchantId,
        String channel,
        String occurredAt
) {
}
