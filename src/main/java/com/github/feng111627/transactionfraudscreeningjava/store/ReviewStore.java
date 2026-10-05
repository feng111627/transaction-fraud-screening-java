package com.github.feng111627.transactionfraudscreeningjava.store;

import com.github.feng111627.transactionfraudscreeningjava.domain.ReviewCase;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存复核工单库。状态迁移的并发安全由 ReviewService 的工单级锁保证。
 */
@Repository
public class ReviewStore {

    private final ConcurrentHashMap<String, ReviewCase> byId = new ConcurrentHashMap<>();

    public ReviewCase save(ReviewCase reviewCase) {
        byId.put(reviewCase.caseId(), reviewCase);
        return reviewCase;
    }

    public Optional<ReviewCase> findById(String caseId) {
        return Optional.ofNullable(byId.get(caseId));
    }

    public Optional<ReviewCase> findByTransactionId(String transactionId) {
        return byId.values().stream()
                .filter(c -> c.transactionId().equals(transactionId))
                .findFirst();
    }

    public List<ReviewCase> findAll() {
        return List.copyOf(byId.values());
    }
}
