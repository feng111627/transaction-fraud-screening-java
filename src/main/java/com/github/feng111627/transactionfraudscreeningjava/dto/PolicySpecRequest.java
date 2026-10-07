package com.github.feng111627.transactionfraudscreeningjava.dto;

import com.github.feng111627.transactionfraudscreeningjava.domain.Role;

import java.math.BigDecimal;

/**
 * 策略内容请求：发布新版本与影响预演共用。
 * 字段为可空包装类型，缺失时按校验错误处理并给出具体原因。
 */
public record PolicySpecRequest(
        String operator,
        Role role,
        String version,
        BigDecimal highAmountThreshold,
        Integer highAmountScore,
        Integer velocityWindowSeconds,
        Integer velocityMaxCount,
        Integer velocityScore,
        Integer watchlistScore,
        Integer reviewThreshold,
        Integer blockThreshold
) {
}
