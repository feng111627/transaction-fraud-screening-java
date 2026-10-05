package com.github.feng111627.transactionfraudscreeningjava.controller;

import com.github.feng111627.transactionfraudscreeningjava.dto.IngestResult;
import com.github.feng111627.transactionfraudscreeningjava.dto.TransactionRequest;
import com.github.feng111627.transactionfraudscreeningjava.exception.ApiException;
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

    public TransactionController(TransactionService transactionService, TransactionStore transactionStore) {
        this.transactionService = transactionService;
        this.transactionStore = transactionStore;
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
}
