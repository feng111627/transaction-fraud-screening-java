package com.github.feng111627.transactionfraudscreeningjava.model;

/** 不可篡改的审计事件（只追加） */
public record AuditEvent(
        long seq,
        long timeEpochMilli,
        AuditAction action,
        String operator,
        String targetRef,
        String detail,
        String result
) {
}
