package com.github.feng111627.transactionfraudscreeningjava.store;

import com.github.feng111627.transactionfraudscreeningjava.domain.AuditEvent;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 追加式审计日志，事件之间以哈希链连接，写入后不可修改。
 * 每条事件的 hash 覆盖 (seq, at, actor, action, subject, details, prevHash)，
 * 任何对历史事件的篡改都会破坏链条，可通过 verify() 检出。
 */
@Repository
public class AuditLog {

    private static final String GENESIS = "GENESIS";

    private final List<AuditEvent> events = new ArrayList<>();
    private String lastHash = GENESIS;

    public synchronized AuditEvent append(String actor, String action, String subject, String details) {
        long seq = events.size() + 1L;
        Instant at = Instant.now();
        String hash = hash(seq, at, actor, action, subject, details, lastHash);
        AuditEvent event = new AuditEvent(seq, at, actor, action, subject, details, lastHash, hash);
        events.add(event);
        lastHash = hash;
        return event;
    }

    public synchronized List<AuditEvent> events() {
        return List.copyOf(events);
    }

    /** 校验整条哈希链是否完整。 */
    public synchronized boolean verify() {
        String prev = GENESIS;
        for (AuditEvent e : events) {
            if (!e.prevHash().equals(prev)) {
                return false;
            }
            String recomputed = hash(e.seq(), e.at(), e.actor(), e.action(), e.subject(), e.details(), e.prevHash());
            if (!recomputed.equals(e.hash())) {
                return false;
            }
            prev = e.hash();
        }
        return true;
    }

    private static String hash(long seq, Instant at, String actor, String action,
                               String subject, String details, String prevHash) {
        String payload = seq + "|" + at + "|" + actor + "|" + action + "|" + subject + "|" + details + "|" + prevHash;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
