package com.github.feng111627.transactionfraudscreeningjava.web;

import com.github.feng111627.transactionfraudscreeningjava.error.ValidationException;
import com.github.feng111627.transactionfraudscreeningjava.model.Disposition;
import com.github.feng111627.transactionfraudscreeningjava.model.Operator;
import com.github.feng111627.transactionfraudscreeningjava.model.ReviewRecord;
import com.github.feng111627.transactionfraudscreeningjava.service.ReviewService;
import com.github.feng111627.transactionfraudscreeningjava.web.dto.Dtos.ResolveReviewRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 人工复核入口。操作人身份来自 X-Operator / X-Roles 请求头。 */
@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private final ReviewService reviewService;
    private final OperatorResolver operatorResolver;

    public ReviewController(ReviewService reviewService, OperatorResolver operatorResolver) {
        this.reviewService = reviewService;
        this.operatorResolver = operatorResolver;
    }

    @PostMapping("/{reviewId}/claim")
    public Map<String, Object> claim(@PathVariable String reviewId, HttpServletRequest http) {
        Operator operator = operatorResolver.resolve(http);
        ReviewRecord review = reviewService.claim(reviewId, operator);
        return Views.review(review);
    }

    /** body: {"action":"APPROVE|REJECT","comment":"..."} */
    @PostMapping("/{reviewId}/resolve")
    public Map<String, Object> resolve(@PathVariable String reviewId,
                                       @RequestBody ResolveReviewRequest req,
                                       HttpServletRequest http) {
        Operator operator = operatorResolver.resolve(http);
        Disposition disposition;
        try {
            disposition = Disposition.valueOf(req.action());
        } catch (Exception e) {
            throw new ValidationException("ACTION_INVALID",
                    "结案动作必须为 APPROVE 或 REJECT，实际=" + req.action());
        }
        if (disposition == Disposition.REVIEW) {
            throw new ValidationException("ACTION_INVALID", "结案动作不能为 REVIEW");
        }
        ReviewRecord review = reviewService.resolve(reviewId, disposition, req.comment(), operator);
        return Views.review(review);
    }

    @GetMapping("/{reviewId}")
    public Map<String, Object> get(@PathVariable String reviewId) {
        return Views.review(reviewService.get(reviewId));
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return reviewService.list().stream().map(Views::review).toList();
    }
}
