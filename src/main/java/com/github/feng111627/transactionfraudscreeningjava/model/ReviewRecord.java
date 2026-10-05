package com.github.feng111627.transactionfraudscreeningjava.model;

/**
 * 人工复核单。
 * <p>状态机：{@code PENDING -> INVESTIGATING -> RESOLVED}，不允许跨级/逆向迁移。
 * 覆盖系统判断时 finalDisposition 可与 systemDisposition 不同，并标记 overridden。
 */
public class ReviewRecord {
    private final String reviewId;
    private final String txnId;
    private final Disposition systemDisposition;
    private final long createdAtEpochMilli;

    private ReviewStatus status;
    private Disposition finalDisposition;
    private String claimedBy;
    private Long claimedAtEpochMilli;
    private String resolvedBy;
    private Long resolvedAtEpochMilli;
    private String comment;
    private boolean overridden;

    private ReviewRecord(String reviewId, String txnId, Disposition systemDisposition,
                         long createdAtEpochMilli) {
        this.reviewId = reviewId;
        this.txnId = txnId;
        this.systemDisposition = systemDisposition;
        this.createdAtEpochMilli = createdAtEpochMilli;
        this.status = ReviewStatus.PENDING;
    }

    public static ReviewRecord open(String reviewId, String txnId,
                                    Disposition systemDisposition, long now) {
        return new ReviewRecord(reviewId, txnId, systemDisposition, now);
    }

    /** PENDING -> INVESTIGATING */
    public void claim(String operator, long now) {
        this.status = ReviewStatus.INVESTIGATING;
        this.claimedBy = operator;
        this.claimedAtEpochMilli = now;
    }

    /** INVESTIGATING -> RESOLVED */
    public void resolve(String operator, long now, Disposition disposition, String comment,
                        boolean overridden) {
        this.status = ReviewStatus.RESOLVED;
        this.resolvedBy = operator;
        this.resolvedAtEpochMilli = now;
        this.finalDisposition = disposition;
        this.comment = comment;
        this.overridden = overridden;
    }

    public String getReviewId() { return reviewId; }
    public String getTxnId() { return txnId; }
    public ReviewStatus getStatus() { return status; }
    public Disposition getSystemDisposition() { return systemDisposition; }
    public Disposition getFinalDisposition() { return finalDisposition; }
    public String getClaimedBy() { return claimedBy; }
    public Long getClaimedAtEpochMilli() { return claimedAtEpochMilli; }
    public String getResolvedBy() { return resolvedBy; }
    public Long getResolvedAtEpochMilli() { return resolvedAtEpochMilli; }
    public String getComment() { return comment; }
    public boolean isOverridden() { return overridden; }
    public long getCreatedAtEpochMilli() { return createdAtEpochMilli; }
}
