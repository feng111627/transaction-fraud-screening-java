package com.github.feng111627.transactionfraudscreeningjava.controller;

import com.github.feng111627.transactionfraudscreeningjava.dto.ReplayRequest;
import com.github.feng111627.transactionfraudscreeningjava.dto.ReplayResult;
import com.github.feng111627.transactionfraudscreeningjava.service.ReplayService;
import org.springframework.web.bind.annotation.*;

/**
 * 历史决策重算入口：按交易当初生效的策略版本与名单状态重算并核对。
 * 只读操作，不改变任何业务数据，重算行为本身写入审计。
 */
@RestController
@RequestMapping("/api/transactions")
public class ReplayController {

    private final ReplayService replayService;

    public ReplayController(ReplayService replayService) {
        this.replayService = replayService;
    }

    @PostMapping("/{transactionId}/replay")
    public ReplayResult replay(@PathVariable String transactionId, @RequestBody ReplayRequest request) {
        return replayService.recompute(transactionId,
                request == null ? null : request.operator());
    }
}
