package com.github.feng111627.transactionfraudscreeningjava.domain;

import java.time.Instant;

/**
 * 人工复核工单。状态流转由 ReviewService 按状态机控制。
 */
public record ReviewCase(
        String caseId,
        String transactionId,
        ReviewStatus status,
        String assignee,
        String resolutionNote,
        Instant openedAt,
        Instant updatedAt
) {
    public ReviewCase withStatus(ReviewStatus newStatus, String operator, String note, Instant at) {
        return new ReviewCase(caseId, transactionId, newStatus, operator, note, openedAt, at);
    }
}
