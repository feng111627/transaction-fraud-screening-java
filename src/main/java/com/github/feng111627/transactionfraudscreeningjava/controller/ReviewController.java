package com.github.feng111627.transactionfraudscreeningjava.controller;

import com.github.feng111627.transactionfraudscreeningjava.domain.ReviewCase;
import com.github.feng111627.transactionfraudscreeningjava.dto.ReviewActionRequest;
import com.github.feng111627.transactionfraudscreeningjava.exception.ApiException;
import com.github.feng111627.transactionfraudscreeningjava.service.ReviewService;
import com.github.feng111627.transactionfraudscreeningjava.store.ReviewStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private final ReviewStore reviewStore;
    private final ReviewService reviewService;

    public ReviewController(ReviewStore reviewStore, ReviewService reviewService) {
        this.reviewStore = reviewStore;
        this.reviewService = reviewService;
    }

    @GetMapping
    public List<ReviewCase> list() {
        return reviewStore.findAll();
    }

    @GetMapping("/{caseId}")
    public ReviewCase get(@PathVariable String caseId) {
        return reviewStore.findById(caseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "复核工单不存在: " + caseId));
    }

    @PostMapping("/{caseId}/actions")
    public ReviewCase act(@PathVariable String caseId, @RequestBody ReviewActionRequest action) {
        return reviewService.transition(caseId, action);
    }
}
