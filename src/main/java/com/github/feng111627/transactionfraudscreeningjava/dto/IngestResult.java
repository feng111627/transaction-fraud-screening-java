package com.github.feng111627.transactionfraudscreeningjava.dto;

import com.github.feng111627.transactionfraudscreeningjava.domain.RiskAssessment;

/**
 * 单笔交易接入结果。
 */
public record IngestResult(
        String transactionId,
        IngestStatus status,
        String rejectReason,
        RiskAssessment assessment
) {
    public enum IngestStatus {
        ACCEPTED,
        DUPLICATE,
        REJECTED
    }
}
