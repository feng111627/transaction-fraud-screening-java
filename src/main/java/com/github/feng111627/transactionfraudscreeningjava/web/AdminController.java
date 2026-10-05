package com.github.feng111627.transactionfraudscreeningjava.web;

import com.github.feng111627.transactionfraudscreeningjava.model.AuditEvent;
import com.github.feng111627.transactionfraudscreeningjava.model.Operator;
import com.github.feng111627.transactionfraudscreeningjava.service.AdminService;
import com.github.feng111627.transactionfraudscreeningjava.web.dto.Dtos.RuleConfigRequest;
import com.github.feng111627.transactionfraudscreeningjava.web.dto.Dtos.WatchEntryRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 管理入口：名单、规则配置版本、历史结论复现、审计轨迹与账本查询。 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;
    private final OperatorResolver operatorResolver;

    public AdminController(AdminService adminService, OperatorResolver operatorResolver) {
        this.adminService = adminService;
        this.operatorResolver = operatorResolver;
    }

    @PostMapping("/watchlist")
    public Map<String, Object> addWatch(@RequestBody WatchEntryRequest req, HttpServletRequest http) {
        Operator operator = operatorResolver.resolve(http);
        var entry = adminService.addWatchEntry(req.type(), req.subject(), req.reason(), operator);
        return Map.of(
                "type", entry.type(),
                "subject", entry.subject(),
                "reason", entry.reason(),
                "addedBy", entry.addedBy(),
                "addedAtEpochMilli", entry.addedAtEpochMilli(),
                "active", entry.active());
    }

    @GetMapping("/watchlist")
    public List<?> listWatch() {
        return adminService.listWatch();
    }

    @GetMapping("/rule-config")
    public Object currentConfig() {
        return adminService.currentConfig();
    }

    @PostMapping("/rule-config")
    public Object updateConfig(@RequestBody RuleConfigRequest req, HttpServletRequest http) {
        Operator operator = operatorResolver.resolve(http);
        return adminService.updateRuleConfig(req, operator);
    }

    /** 用交易当时的配置版本重跑引擎，验证历史结论可复现 */
    @PostMapping("/transactions/{txnId}/reproduce")
    public Map<String, Object> reproduce(@PathVariable String txnId, HttpServletRequest http) {
        Operator operator = operatorResolver.resolve(http);
        return Views.evaluation(adminService.reproduce(txnId, operator));
    }

    @GetMapping("/transactions")
    public List<Map<String, Object>> listTxns() {
        return Views.txns(adminService.listTxns());
    }

    @GetMapping("/audits")
    public List<AuditEvent> audits() {
        return adminService.audits();
    }
}
