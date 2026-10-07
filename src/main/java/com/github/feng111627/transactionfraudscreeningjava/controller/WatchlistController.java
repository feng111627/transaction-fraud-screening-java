package com.github.feng111627.transactionfraudscreeningjava.controller;

import com.github.feng111627.transactionfraudscreeningjava.domain.Role;
import com.github.feng111627.transactionfraudscreeningjava.service.WatchlistService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 名单管理入口。增删均要求请求携带 operator 与 role=POLICY_ADMIN，
 * 越权或缺参的尝试被拒绝并留痕，不对名单产生任何改动。
 */
@RestController
@RequestMapping("/api/watchlist")
public class WatchlistController {

    private final WatchlistService watchlistService;

    public WatchlistController(WatchlistService watchlistService) {
        this.watchlistService = watchlistService;
    }

    @PostMapping("/{accountId}")
    public Map<String, Object> add(@PathVariable String accountId, @RequestBody Map<String, String> body) {
        String reason = body.getOrDefault("reason", "未说明");
        String operator = body.get("operator");
        Role role = parseRole(body.get("role"));
        long version = watchlistService.add(accountId, reason, operator, role);
        return status(accountId, "ADDED", version);
    }

    @DeleteMapping("/{accountId}")
    public Map<String, Object> remove(@PathVariable String accountId,
                                      @RequestParam(required = false) String operator,
                                      @RequestParam(required = false) String role) {
        long version = watchlistService.remove(accountId, operator, parseRole(role));
        return status(accountId, "REMOVED", version);
    }

    private Role parseRole(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Role.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Map<String, Object> status(String accountId, String op, long version) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accountId", accountId);
        result.put("operation", op);
        result.put("watchlistVersion", version);
        return result;
    }
}
