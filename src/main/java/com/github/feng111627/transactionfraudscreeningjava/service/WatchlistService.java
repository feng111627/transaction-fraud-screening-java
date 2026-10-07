package com.github.feng111627.transactionfraudscreeningjava.service;

import com.github.feng111627.transactionfraudscreeningjava.domain.Role;
import com.github.feng111627.transactionfraudscreeningjava.dto.WatchlistRequest;
import com.github.feng111627.transactionfraudscreeningjava.exception.ApiException;
import com.github.feng111627.transactionfraudscreeningjava.store.AuditLog;
import com.github.feng111627.transactionfraudscreeningjava.store.WatchlistStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 名单管理服务：与策略发布同级的权限与留痕要求。
 *
 * - 只有 POLICY_ADMIN 可以增删名单；越权尝试被拒绝、留痕且名单与版本号不变；
 * - 每次成功变更名单版本号 +1，并保留该版本的完整快照供历史重算；
 * - 删除不存在的条目属于"当前状态不允许"，拒绝（404）并留痕。
 */
@Service
public class WatchlistService {

    private final WatchlistStore watchlistStore;
    private final AuditLog auditLog;

    public WatchlistService(WatchlistStore watchlistStore, AuditLog auditLog) {
        this.watchlistStore = watchlistStore;
        this.auditLog = auditLog;
    }

    public long add(String accountId, WatchlistRequest request) {
        checkPermission(request, accountId);
        String reason = request.reason() == null || request.reason().isBlank() ? "未说明" : request.reason();
        watchlistStore.add(accountId, reason);
        long version = watchlistStore.version();
        auditLog.append(request.operator(), "WATCHLIST_ADD", accountId,
                "reason=" + reason + " watchlistVersion=v" + version);
        return version;
    }

    public long remove(String accountId, WatchlistRequest request) {
        checkPermission(request, accountId);
        if (!watchlistStore.remove(accountId)) {
            auditLog.append(request.operator(), "WATCHLIST_REMOVE_REJECTED", accountId,
                    "账户不在名单中，删除被拒绝");
            throw new ApiException(HttpStatus.NOT_FOUND, "账户不在名单中: " + accountId);
        }
        long version = watchlistStore.version();
        auditLog.append(request.operator(), "WATCHLIST_REMOVE", accountId,
                "watchlistVersion=v" + version);
        return version;
    }

    private void checkPermission(WatchlistRequest request, String accountId) {
        if (request == null || request.operator() == null || request.operator().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "operator 不能为空");
        }
        if (request.role() != Role.POLICY_ADMIN) {
            auditLog.append(request.operator(), "WATCHLIST_PERMISSION_DENIED", accountId,
                    "角色 " + request.role() + " 无名单管理权限，需要 POLICY_ADMIN");
            throw new ApiException(HttpStatus.FORBIDDEN, "名单管理操作需要 POLICY_ADMIN 角色");
        }
    }
}
