package com.github.feng111627.transactionfraudscreeningjava.web;

import com.github.feng111627.transactionfraudscreeningjava.model.Operator;
import com.github.feng111627.transactionfraudscreeningjava.model.TxnRecord;
import com.github.feng111627.transactionfraudscreeningjava.service.TransactionService;
import com.github.feng111627.transactionfraudscreeningjava.web.dto.Dtos.BatchSubmitRequest;
import com.github.feng111627.transactionfraudscreeningjava.web.dto.Dtos.SubmitTxnRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 交易入口（REST）：单笔、批量、按 txnId/requestId 查询。 */
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionService transactionService;
    private final OperatorResolver operatorResolver;

    public TransactionController(TransactionService transactionService,
                                 OperatorResolver operatorResolver) {
        this.transactionService = transactionService;
        this.operatorResolver = operatorResolver;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> submitSingle(@RequestBody SubmitTxnRequest req,
                                                            HttpServletRequest http) {
        Operator operator = operatorResolver.resolve(http);
        TxnRecord txn = transactionService.submitSingle(req, "REST", operator.name());
        return ResponseEntity.status(HttpStatus.OK).body(Views.txn(txn));
    }

    @PostMapping("/batch")
    public Map<String, Object> submitBatch(@RequestBody BatchSubmitRequest req,
                                           HttpServletRequest http) {
        Operator operator = operatorResolver.resolve(http);
        List<TxnRecord> results = transactionService.submitBatch(
                req == null ? null : req.transactions(), operator.name());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("submitted", results.size());
        resp.put("results", Views.txns(results));
        return resp;
    }

    @GetMapping("/{txnId}")
    public Map<String, Object> getByTxnId(@PathVariable String txnId) {
        return Views.txn(transactionService.requireByTxnId(txnId));
    }

    @GetMapping(params = "requestId")
    public Map<String, Object> getByRequestId(@RequestParam String requestId) {
        return Views.txn(transactionService.requireByRequestId(requestId));
    }
}
