package com.github.feng111627.transactionfraudscreeningjava.domain;

import java.time.Instant;

/**
 * 审计事件。通过 prevHash/hash 构成哈希链，追加后不可篡改。
 */
public record AuditEvent(
        long seq,
        Instant at,
        String actor,
        String action,
        String subject,
        String details,
        String prevHash,
        String hash
) {
}
