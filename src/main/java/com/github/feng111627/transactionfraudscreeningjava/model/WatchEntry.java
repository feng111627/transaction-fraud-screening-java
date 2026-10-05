package com.github.feng111627.transactionfraudscreeningjava.model;

/** 名单条目 */
public record WatchEntry(
        ListType type,
        String subject,
        String reason,
        String addedBy,
        long addedAtEpochMilli,
        boolean active
) {
}
