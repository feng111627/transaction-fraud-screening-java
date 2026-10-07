package com.github.feng111627.transactionfraudscreeningjava.controller;

import com.github.feng111627.transactionfraudscreeningjava.dto.WatchlistRequest;
import com.github.feng111627.transactionfraudscreeningjava.service.WatchlistService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 名单管理入口。增删均要求 POLICY_ADMIN 角色（在请求体中声明操作者与角色），
 * 越权或状态不允许的尝试会被拒绝、留痕且无副作用。
 */
@RestController
@RequestMapping("/api/watchlist")
public class WatchlistController {

    private final WatchlistService watchlistService;

    public WatchlistController(WatchlistService watchlistService) {
        this.watchlistService = watchlistService;
    }

    @PostMapping("/{accountId}")
    public Map<String, Object> add(@PathVariable String accountId, @RequestBody WatchlistRequest request) {
        long version = watchlistService.add(accountId, request);
        return status(accountId, "ADDED", version);
    }

    @DeleteMapping("/{accountId}")
    public Map<String, Object> remove(@PathVariable String accountId, @RequestBody WatchlistRequest request) {
        long version = watchlistService.remove(accountId, request);
        return status(accountId, "REMOVED", version);
    }

    private Map<String, Object> status(String accountId, String op, long version) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accountId", accountId);
        result.put("operation", op);
        result.put("watchlistVersion", version);
        return result;
    }
}
