package com.github.feng111627.transactionfraudscreeningjava.controller;

import com.github.feng111627.transactionfraudscreeningjava.store.AuditLog;
import com.github.feng111627.transactionfraudscreeningjava.store.WatchlistStore;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/watchlist")
public class WatchlistController {

    private final WatchlistStore watchlistStore;
    private final AuditLog auditLog;

    public WatchlistController(WatchlistStore watchlistStore, AuditLog auditLog) {
        this.watchlistStore = watchlistStore;
        this.auditLog = auditLog;
    }

    @PostMapping("/{accountId}")
    public Map<String, Object> add(@PathVariable String accountId, @RequestBody Map<String, String> body) {
        String reason = body.getOrDefault("reason", "未说明");
        watchlistStore.add(accountId, reason);
        auditLog.append("api", "WATCHLIST_ADD", accountId, "reason=" + reason);
        return status(accountId, "ADDED");
    }

    @DeleteMapping("/{accountId}")
    public Map<String, Object> remove(@PathVariable String accountId) {
        watchlistStore.remove(accountId);
        auditLog.append("api", "WATCHLIST_REMOVE", accountId, "");
        return status(accountId, "REMOVED");
    }

    private Map<String, Object> status(String accountId, String op) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accountId", accountId);
        result.put("operation", op);
        result.put("watchlistVersion", watchlistStore.version());
        return result;
    }
}
