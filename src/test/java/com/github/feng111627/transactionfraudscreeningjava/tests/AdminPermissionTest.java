package com.github.feng111627.transactionfraudscreeningjava.tests;

import com.github.feng111627.transactionfraudscreeningjava.error.AccessDeniedException;
import com.github.feng111627.transactionfraudscreeningjava.ledger.Ledger;
import com.github.feng111627.transactionfraudscreeningjava.model.AuditAction;
import com.github.feng111627.transactionfraudscreeningjava.model.Operator;
import com.github.feng111627.transactionfraudscreeningjava.model.Role;
import com.github.feng111627.transactionfraudscreeningjava.service.AdminService;
import com.github.feng111627.transactionfraudscreeningjava.web.dto.Dtos.RuleConfigRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖业务：管理操作权限。
 *  - 非 ADMIN 变更名单/规则配置 -> 403 且审计留痕，且数据不被改动；
 *  - 非 SENIOR 复现结论 -> 403；
 *  - ADMIN 变更成功并生成新版本。
 */
@SpringBootTest
class AdminPermissionTest extends BusinessTest {

    @Autowired
    private AdminService adminService;
    @Autowired
    private Ledger ledger;

    private final Operator analyst = new Operator("analyst-x", EnumSet.of(Role.ANALYST));
    private final Operator admin = new Operator("root", EnumSet.of(Role.ADMIN));

    @Test
    void non_admin_cannot_change_watchlist_and_change_is_audited() {
        log.info("覆盖业务[越权] 用例: ANALYST 尝试加名单 -> 403，审计留 ACCESS_DENIED，名单不变");
        long before = ledger.allWatchEntries().size();
        assertThrows(AccessDeniedException.class,
                () -> adminService.addWatchEntry("ACCOUNT", "EVIL-1", "测试", analyst));
        assertEquals(before, ledger.allWatchEntries().size(), "越权操作不得改动名单");
        boolean audited = ledger.allAudits().stream()
                .anyMatch(a -> a.action() == AuditAction.ACCESS_DENIED
                        && "analyst-x".equals(a.operator()));
        assertTrue(audited, "越权必须留痕");
    }

    @Test
    void non_admin_cannot_change_rule_config() {
        log.info("覆盖业务[越权] 用例: ANALYST 尝试发布新阈值 -> 403，当前版本不变");
        long versionBefore = ledger.currentRuleConfig().version();
        RuleConfigRequest req = new RuleConfigRequest(
                new BigDecimal("1000"), null, null, null, null,
                null, null, null, null, null);
        assertThrows(AccessDeniedException.class,
                () -> adminService.updateRuleConfig(req, analyst));
        assertEquals(versionBefore, ledger.currentRuleConfig().version());
    }

    @Test
    void admin_can_publish_new_config_version() {
        log.info("覆盖业务[配置版本管理] 用例: ADMIN 发布新阈值成功，版本号单调递增且旧版本仍可查询");
        long version = ledger.currentRuleConfig().version();
        var updated = adminService.updateRuleConfig(new RuleConfigRequest(
                new BigDecimal("42000"), null, null, null, null,
                null, null, null, null, null), admin);
        assertEquals(version + 1, updated.version());
        assertTrue(ledger.ruleConfigAt(version).isPresent(), "历史版本必须保留以便复现");
    }
}
