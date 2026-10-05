package com.github.feng111627.transactionfraudscreeningjava.model;

import java.math.BigDecimal;

/**
 * 交易账本记录。
 * <p>被拒绝的交易也会落账（status=REJECTED 并记录 rejectReason），但不会进入风控频率计数。
 */
public class TxnRecord {
    private final String requestId;
    private final String txnId;
    private final String accountId;
    private final String targetId;
    private final BigDecimal amount;
    private final String currency;
    private final long txnTimeEpochMilli;
    private final long receivedAtEpochMilli;
    private final String source;
    private final TxnStatus status;
    private final RejectReason rejectReason;
    private volatile RiskEvaluation evaluation;
    /** 进入账户锁开始判定的全局处理序号（判定顺序），未判定为 -1 */
    private volatile long processedSeq = -1L;
    /** 进入账户锁开始判定的处理时刻（滑动窗口基准），未判定为 -1 */
    private volatile long processedAtMillis = -1L;

    private TxnRecord(String requestId, String txnId, String accountId, String targetId,
                      BigDecimal amount, String currency, long txnTimeEpochMilli,
                      long receivedAtEpochMilli, String source,
                      TxnStatus status, RejectReason rejectReason) {
        this.requestId = requestId;
        this.txnId = txnId;
        this.accountId = accountId;
        this.targetId = targetId;
        this.amount = amount;
        this.currency = currency;
        this.txnTimeEpochMilli = txnTimeEpochMilli;
        this.receivedAtEpochMilli = receivedAtEpochMilli;
        this.source = source;
        this.status = status;
        this.rejectReason = rejectReason;
    }

    /** 构造一条通过字段校验、已接入的交易 */
    public static TxnRecord accepted(String requestId, String txnId, String accountId, String targetId,
                                     BigDecimal amount, String currency, long txnTimeEpochMilli,
                                     long receivedAtEpochMilli, String source) {
        return new TxnRecord(requestId, txnId, accountId, targetId, amount, currency,
                txnTimeEpochMilli, receivedAtEpochMilli, source, TxnStatus.ACCEPTED, null);
    }

    /** 构造一条因明确原因被拒绝的交易（留痕，但不参与风控计数） */
    public static TxnRecord rejected(String requestId, String accountId, long receivedAtEpochMilli,
                                     String source, RejectReason reason) {
        return new TxnRecord(requestId, null, accountId, null, null, null, 0L,
                receivedAtEpochMilli, source, TxnStatus.REJECTED, reason);
    }

    /** 幂等重放时发现请求体与首次提交不一致 */
    public static TxnRecord idempotencyConflict(long receivedAtEpochMilli, String source) {
        return new TxnRecord(null, null, null, null, null, null, 0L,
                receivedAtEpochMilli, source, TxnStatus.REJECTED, RejectReason.IDEMPOTENCY_MISMATCH);
    }

    public void attachEvaluation(RiskEvaluation evaluation) {
        this.evaluation = evaluation;
    }

    public long getProcessedSeq() { return processedSeq; }

    public long getProcessedAtMillis() { return processedAtMillis; }

    public void markProcessed(long seq, long atMillis) {
        this.processedSeq = seq;
        this.processedAtMillis = atMillis;
    }

    public String getRequestId() { return requestId; }
    public String getTxnId() { return txnId; }
    public String getAccountId() { return accountId; }
    public String getTargetId() { return targetId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public long getTxnTimeEpochMilli() { return txnTimeEpochMilli; }
    public long getReceivedAtEpochMilli() { return receivedAtEpochMilli; }
    public String getSource() { return source; }
    public TxnStatus getStatus() { return status; }
    public RejectReason getRejectReason() { return rejectReason; }
    public RiskEvaluation getEvaluation() { return evaluation; }
}
