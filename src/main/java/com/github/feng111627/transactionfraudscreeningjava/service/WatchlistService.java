package com.github.feng111627.transactionfraudscreeningjava.service;

import com.github.feng111627.transactionfraudscreeningjava.domain.Role;
import com.github.feng111627.transactionfraudscreeningjava.exception.ApiException;
import com.github.feng111627.transactionfraudscreeningjava.store.AuditLog;
import com.github.feng111627.transactionfraudscreeningjava.store.WatchlistStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 名单管理服务：增删名单需要 POLICY_ADMIN 权限，全部操作留痕。
 * 权限不足或参数非法的尝试直接拒绝并写审计，不对名单做任何改动。
 */
@Service
public class WatchlistService {

    private final WatchlistStore watchlistStore;
    private final AuditLog auditLog;

    public WatchlistService(WatchlistStore watchlistStore, AuditLog auditLog) {
        this.watchlistStore = watchlistStore;
        this.auditLog = auditLog;
    }

    public long add(String accountId, String reason, String operator, Role role) {
        checkPermission(accountId, "ADD", operator, role);
        watchlistStore.add(accountId, reason);
        auditLog.append(operator, "WATCHLIST_ADD", accountId, "reason=" + reason);
        return watchlistStore.version();
    }

    public long remove(String accountId, String operator, Role role) {
        checkPermission(accountId, "REMOVE", operator, role);
        watchlistStore.remove(accountId);
        auditLog.append(operator, "WATCHLIST_REMOVE", accountId, "");
        return watchlistStore.version();
    }

    private void checkPermission(String accountId, String op, String operator, Role role) {
        String actor = operator == null || operator.isBlank() ? "unknown" : operator;
        if (operator == null || operator.isBlank() || role == null) {
            auditLog.append(actor, "WATCHLIST_OP_REJECTED", accountId,
                    op + " 缺少 operator/role");
            throw new ApiException(HttpStatus.BAD_REQUEST, "operator/role 均不能为空");
        }
        if (role != Role.POLICY_ADMIN) {
            auditLog.append(operator, "PERMISSION_DENIED", accountId,
                    op + " 名单仅 POLICY_ADMIN 可执行，当前角色=" + role);
            throw new ApiException(HttpStatus.FORBIDDEN, "名单增删仅 POLICY_ADMIN 可执行");
        }
    }
}
