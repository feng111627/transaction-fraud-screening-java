package com.github.feng111627.transactionfraudscreeningjava.controller;

import com.github.feng111627.transactionfraudscreeningjava.dto.IngestResult;
import com.github.feng111627.transactionfraudscreeningjava.dto.ReplayRequest;
import com.github.feng111627.transactionfraudscreeningjava.dto.ReplayResult;
import com.github.feng111627.transactionfraudscreeningjava.dto.TransactionRequest;
import com.github.feng111627.transactionfraudscreeningjava.exception.ApiException;
import com.github.feng111627.transactionfraudscreeningjava.service.ReplayService;
import com.github.feng111627.transactionfraudscreeningjava.service.TransactionService;
import com.github.feng111627.transactionfraudscreeningjava.store.TransactionStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionService transactionService;
    private final TransactionStore transactionStore;
    private final ReplayService replayService;

    public TransactionController(TransactionService transactionService,
                                 TransactionStore transactionStore,
                                 ReplayService replayService) {
        this.transactionService = transactionService;
        this.transactionStore = transactionStore;
        this.replayService = replayService;
    }

    @PostMapping
    public IngestResult ingest(@RequestBody TransactionRequest request) {
        return transactionService.ingest(request);
    }

    @PostMapping("/batch")
    public List<IngestResult> ingestBatch(@RequestBody List<TransactionRequest> requests) {
        return transactionService.ingestBatch(requests);
    }

    @GetMapping("/{transactionId}")
    public IngestResult get(@PathVariable String transactionId) {
        return transactionStore.findById(transactionId)
                .map(stored -> new IngestResult(transactionId,
                        IngestResult.IngestStatus.ACCEPTED, null, stored.assessment()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "交易不存在: " + transactionId));
    }

    /** 按交易当时生效的策略版本与名单状态重算核对（只读，不改任何业务数据）。 */
    @PostMapping("/{transactionId}/replay")
    public ReplayResult replay(@PathVariable String transactionId, @RequestBody ReplayRequest request) {
        return replayService.replay(transactionId, request);
    }
}
