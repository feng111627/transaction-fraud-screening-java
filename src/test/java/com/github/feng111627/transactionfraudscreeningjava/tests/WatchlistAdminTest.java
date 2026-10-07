package com.github.feng111627.transactionfraudscreeningjava.tests;

import com.github.feng111627.transactionfraudscreeningjava.domain.AuditEvent;
import com.github.feng111627.transactionfraudscreeningjava.domain.Role;
import com.github.feng111627.transactionfraudscreeningjava.dto.WatchlistRequest;
import com.github.feng111627.transactionfraudscreeningjava.exception.ApiException;
import com.github.feng111627.transactionfraudscreeningjava.service.WatchlistService;
import com.github.feng111627.transactionfraudscreeningjava.store.AuditLog;
import com.github.feng111627.transactionfraudscreeningjava.store.WatchlistStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("名单管理：权限 / 留痕 / 版本递增 / 无副作用")
@ExtendWith(CaseResultLogger.class)
class WatchlistAdminTest {

    private WatchlistStore watchlistStore;
    private AuditLog auditLog;
    private WatchlistService watchlistService;

    @BeforeEach
    void setUp() {
        watchlistStore = new WatchlistStore();
        auditLog = new AuditLog();
        watchlistService = new WatchlistService(watchlistStore, auditLog);
    }

    private WatchlistRequest req(String operator, Role role) {
        return new WatchlistRequest(operator, role, "涉诈账户");
    }

    @Test
    @DisplayName("POLICY_ADMIN 增删成功：版本递增、快照可查、操作留痕")
    void adminCanAddAndRemove() {
        long v1 = watchlistService.add("ACC-W1", req("ops1", Role.POLICY_ADMIN));
        assertEquals(1, v1);
        assertTrue(watchlistStore.find("ACC-W1").isPresent());
        assertEquals("涉诈账户", watchlistStore.find("ACC-W1").orElseThrow().reason());

        long v2 = watchlistService.remove("ACC-W1", req("ops1", Role.POLICY_ADMIN));
        assertEquals(2, v2);
        assertTrue(watchlistStore.find("ACC-W1").isEmpty());

        // 历史版本快照仍完整保留（供重算还原）
        assertTrue(watchlistStore.snapshotAt(1).orElseThrow().find("ACC-W1").isPresent());
        assertTrue(watchlistStore.snapshotAt(2).orElseThrow().find("ACC-W1").isEmpty());

        List<AuditEvent> ops = auditLog.events().stream()
                .filter(e -> e.action().startsWith("WATCHLIST_")).toList();
        assertEquals(2, ops.size());
        assertEquals("WATCHLIST_ADD", ops.get(0).action());
        assertEquals("ops1", ops.get(0).actor());
        assertEquals("WATCHLIST_REMOVE", ops.get(1).action());
        assertTrue(auditLog.verify());
    }

    @Test
    @DisplayName("非 POLICY_ADMIN 增删被拒绝、留痕且名单与版本不变")
    void nonAdminRejectedWithoutSideEffects() {
        watchlistService.add("ACC-W2", req("ops1", Role.POLICY_ADMIN));
        long versionBefore = watchlistStore.version();
        int auditBefore = auditLog.events().size();

        ApiException e1 = assertThrows(ApiException.class,
                () -> watchlistService.add("ACC-W3", req("ana1", Role.ANALYST)));
        assertEquals(HttpStatus.FORBIDDEN, e1.status());
        ApiException e2 = assertThrows(ApiException.class,
                () -> watchlistService.remove("ACC-W2", req("sup1", Role.SUPERVISOR)));
        assertEquals(HttpStatus.FORBIDDEN, e2.status());
        ApiException e3 = assertThrows(ApiException.class,
                () -> watchlistService.remove("ACC-W2", req("aud1", Role.AUDITOR)));
        assertEquals(HttpStatus.FORBIDDEN, e3.status());

        // 无副作用：名单内容与版本号均未变
        assertTrue(watchlistStore.find("ACC-W2").isPresent());
        assertTrue(watchlistStore.find("ACC-W3").isEmpty());
        assertEquals(versionBefore, watchlistStore.version());

        // 三次越权尝试全部留痕
        List<AuditEvent> denied = auditLog.events().stream().skip(auditBefore)
                .filter(e -> e.action().equals("WATCHLIST_PERMISSION_DENIED")).toList();
        assertEquals(3, denied.size());
        assertTrue(auditLog.verify());
    }

    @Test
    @DisplayName("删除不存在的账户被拒绝（404）、留痕且版本不变")
    void removeNonExistentRejected() {
        long versionBefore = watchlistStore.version();

        ApiException e = assertThrows(ApiException.class,
                () -> watchlistService.remove("ACC-NOPE", req("ops1", Role.POLICY_ADMIN)));
        assertEquals(HttpStatus.NOT_FOUND, e.status());

        assertEquals(versionBefore, watchlistStore.version());
        assertTrue(auditLog.events().stream().anyMatch(ev ->
                ev.action().equals("WATCHLIST_REMOVE_REJECTED") && ev.subject().equals("ACC-NOPE")));
        assertTrue(auditLog.verify());
    }

    @Test
    @DisplayName("operator 为空被拒绝（400）")
    void blankOperatorRejected() {
        ApiException e = assertThrows(ApiException.class,
                () -> watchlistService.add("ACC-W4", req(" ", Role.POLICY_ADMIN)));
        assertEquals(HttpStatus.BAD_REQUEST, e.status());
        assertTrue(watchlistStore.find("ACC-W4").isEmpty());
        assertEquals(0, watchlistStore.version());
    }
}
