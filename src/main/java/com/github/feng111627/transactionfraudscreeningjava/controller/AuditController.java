package com.github.feng111627.transactionfraudscreeningjava.controller;

import com.github.feng111627.transactionfraudscreeningjava.domain.AuditEvent;
import com.github.feng111627.transactionfraudscreeningjava.store.AuditLog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditLog auditLog;

    public AuditController(AuditLog auditLog) {
        this.auditLog = auditLog;
    }

    @GetMapping
    public List<AuditEvent> events() {
        return auditLog.events();
    }

    @GetMapping("/verify")
    public Map<String, Object> verify() {
        return Map.of("intact", auditLog.verify(), "eventCount", auditLog.events().size());
    }
}
