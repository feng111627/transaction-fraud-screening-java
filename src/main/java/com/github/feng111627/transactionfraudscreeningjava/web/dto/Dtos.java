package com.github.feng111627.transactionfraudscreeningjava.web.dto;

import java.math.BigDecimal;
import java.util.List;

/** 接口数据传输对象 */
public final class Dtos {
    private Dtos() {}

    public record SubmitTxnRequest(
            String requestId, String accountId, String targetId,
            BigDecimal amount, String currency, Long txnTimeEpochMilli, String source
    ) {}

    public record BatchSubmitRequest(List<SubmitTxnRequest> transactions) {}

    public record ResolveReviewRequest(String action, String comment) {}

    public record WatchEntryRequest(String type, String subject, String reason) {}

    public record RuleConfigRequest(
            BigDecimal largeAmount, Integer largeAmountScore,
            Long frequencyWindowSeconds, Integer frequencyLimit, Integer frequencyScore,
            Integer watchAccountScore, Integer watchTargetScore, Integer newPayeeScore,
            Integer reviewThreshold, Integer rejectThreshold
    ) {}
}
