package com.github.feng111627.transactionfraudscreeningjava.tests;

import com.github.feng111627.transactionfraudscreeningjava.domain.Role;
import com.github.feng111627.transactionfraudscreeningjava.exception.ApiException;
import com.github.feng111627.transactionfraudscreeningjava.service.WatchlistService;
import com.github.feng111627.transactionfraudscreeningjava.store.AuditLog;
import com.github.feng111627.transactionfraudscreeningjava.store.WatchlistStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("名单管理：POLICY_ADMIN 权限边界与留痕")
@ExtendWith(CaseResultLogger.class)
class WatchlistPermissionTest {

    private WatchlistStore watchlistStore;
    private AuditLog auditLog;
    private WatchlistService watchlistService;

    @BeforeEach
    void setUp() {
        watchlistStore = new WatchlistStore();
        auditLog = new AuditLog();
        watchlistService = new WatchlistService(watchlistStore, auditLog);
    }

    @Test
    @DisplayName("POLICY_ADMIN 增删名单成功，版本号递增且留痕")
    void adminCanMutate() {
        long v1 = watchlistService.add("A1", "涉诈账户", "ops1", Role.POLICY_ADMIN);
        assertEquals(1, v1);
        assertTrue(watchlistStore.find("A1").isPresent());

        long v2 = watchlistService.remove("A1", "ops1", Role.POLICY_ADMIN);
        assertEquals(2, v2);
        assertTrue(watchlistStore.find("A1").isEmpty());

        assertTrue(auditLog.events().stream().anyMatch(e -> e.action().equals("WATCHLIST_ADD")
                && e.actor().equals("ops1") && e.subject().equals("A1")));
        assertTrue(auditLog.events().stream().anyMatch(e -> e.action().equals("WATCHLIST_REMOVE")));
        assertTrue(auditLog.verify());
    }

    @Test
    @DisplayName("非管理角色增删名单被拒绝（403），名单与版本不变且留痕")
    void nonAdminForbidden() {
        watchlistService.add("A1", "涉诈账户", "ops1", Role.POLICY_ADMIN);
        long versionBefore = watchlistStore.version();

        ApiException addEx = assertThrows(ApiException.class,
                () -> watchlistService.add("A2", "测试", "ana1", Role.ANALYST));
        assertEquals(HttpStatus.FORBIDDEN, addEx.status());
        ApiException removeEx = assertThrows(ApiException.class,
                () -> watchlistService.remove("A1", "aud1", Role.AUDITOR));
        assertEquals(HttpStatus.FORBIDDEN, removeEx.status());

        // 无副作用：名单内容与版本均未变化
        assertTrue(watchlistStore.find("A1").isPresent());
        assertTrue(watchlistStore.find("A2").isEmpty());
        assertEquals(versionBefore, watchlistStore.version());
        // 越权留痕
        assertEquals(2, auditLog.events().stream()
                .filter(e -> e.action().equals("PERMISSION_DENIED")).count());
        assertTrue(auditLog.verify());
    }

    @Test
    @DisplayName("缺少 operator/role 的名单操作被拒绝（400），无副作用且留痕")
    void missingOperatorRejected() {
        ApiException ex = assertThrows(ApiException.class,
                () -> watchlistService.add("A1", "测试", null, Role.POLICY_ADMIN));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        ApiException ex2 = assertThrows(ApiException.class,
                () -> watchlistService.remove("A1", "ops1", null));
        assertEquals(HttpStatus.BAD_REQUEST, ex2.status());

        assertTrue(watchlistStore.find("A1").isEmpty());
        assertEquals(0, watchlistStore.version());
        assertEquals(2, auditLog.events().stream()
                .filter(e -> e.action().equals("WATCHLIST_OP_REJECTED")).count());
        assertTrue(auditLog.verify());
    }
}
