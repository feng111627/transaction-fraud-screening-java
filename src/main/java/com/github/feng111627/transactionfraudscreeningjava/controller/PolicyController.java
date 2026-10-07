package com.github.feng111627.transactionfraudscreeningjava.controller;

import com.github.feng111627.transactionfraudscreeningjava.domain.PolicyVersion;
import com.github.feng111627.transactionfraudscreeningjava.dto.PolicyPublishRequest;
import com.github.feng111627.transactionfraudscreeningjava.dto.PreviewRequest;
import com.github.feng111627.transactionfraudscreeningjava.dto.PreviewResult;
import com.github.feng111627.transactionfraudscreeningjava.service.PolicyService;
import com.github.feng111627.transactionfraudscreeningjava.service.PreviewService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/policies")
public class PolicyController {

    private final PolicyService policyService;
    private final PreviewService previewService;

    public PolicyController(PolicyService policyService, PreviewService previewService) {
        this.policyService = policyService;
        this.previewService = previewService;
    }

    /** 发布新策略版本并切换为当前生效（仅 POLICY_ADMIN）。 */
    @PostMapping
    public PolicyVersion publish(@RequestBody PolicyPublishRequest request) {
        return policyService.publish(request);
    }

    /**
     * 影响预演：拿候选策略对历史交易试算（仅 POLICY_ADMIN）。
     * 纯只读，候选策略不会被发布，也不影响实时判定。
     */
    @PostMapping("/preview")
    public PreviewResult preview(@RequestBody PreviewRequest request) {
        return previewService.preview(request);
    }

    @GetMapping
    public List<PolicyVersion> list() {
        return policyService.list();
    }

    @GetMapping("/active")
    public PolicyVersion active() {
        return policyService.active();
    }
}
