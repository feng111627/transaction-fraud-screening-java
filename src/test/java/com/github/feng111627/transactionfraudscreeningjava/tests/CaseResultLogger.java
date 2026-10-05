package com.github.feng111627.transactionfraudscreeningjava.tests;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试观察器：在日志中输出每个业务测试类的覆盖范围，
 * 以及每个用例的名称与通过/失败结果。
 */
public class CaseResultLogger implements TestWatcher, BeforeAllCallback, AfterAllCallback {

    private static final Logger log = LoggerFactory.getLogger(CaseResultLogger.class);
    private final AtomicInteger passed = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();

    @Override
    public void beforeAll(ExtensionContext context) {
        log.info("==== 开始业务测试: {} ====", context.getDisplayName());
    }

    @Override
    public void testSuccessful(ExtensionContext context) {
        passed.incrementAndGet();
        log.info("[通过] {}", context.getDisplayName());
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        failed.incrementAndGet();
        log.error("[失败] {} -> {}", context.getDisplayName(), cause.getMessage());
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        log.warn("[跳过] {}", context.getDisplayName());
    }

    @Override
    public void afterAll(ExtensionContext context) {
        log.info("==== 业务测试结束: {} 通过={} 失败={} ====",
                context.getDisplayName(), passed.get(), failed.get());
    }
}
