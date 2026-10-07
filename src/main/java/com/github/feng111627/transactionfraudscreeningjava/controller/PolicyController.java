package com.github.feng111627.transactionfraudscreeningjava.controller;

import com.github.feng111627.transactionfraudscreeningjava.domain.PolicyVersion;
import com.github.feng111627.transactionfraudscreeningjava.dto.DryRunResult;
import com.github.feng111627.transactionfraudscreeningjava.dto.PolicySpecRequest;
import com.github.feng111627.transactionfraudscreeningjava.service.DryRunService;
import com.github.feng111627.transactionfraudscreeningjava.service.PolicyService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 策略运营入口：发布新版本（仅 POLICY_ADMIN）、查询版本列表与当前生效版本、
 * 对候选策略做只读影响预演。
 */
@RestController
@RequestMapping("/api/policies")
public class PolicyController {

    private final PolicyService policyService;
    private final DryRunService dryRunService;

    public PolicyController(PolicyService policyService, DryRunService dryRunService) {
        this.policyService = policyService;
        this.dryRunService = dryRunService;
    }

    @PostMapping
    public PolicyVersion publish(@RequestBody PolicySpecRequest request) {
        return policyService.publish(request);
    }

    /** 影响预演：只读计算，候选策略不会发布，对实时判定无影响。 */
    @PostMapping("/dry-run")
    public DryRunResult dryRun(@RequestBody PolicySpecRequest request) {
        return dryRunService.dryRun(request);
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
