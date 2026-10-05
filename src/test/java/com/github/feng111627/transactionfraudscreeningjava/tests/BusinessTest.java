package com.github.feng111627.transactionfraudscreeningjava.tests;

import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 业务测试基类：在日志中打印每个用例所属业务、用例名与执行结果，
 * 使"跑单测时的日志能看出覆盖了哪些业务以及各业务的用例与结果"。
 */
public abstract class BusinessTest
        implements BeforeTestExecutionCallback, AfterTestExecutionCallback {

    protected final Logger log = LoggerFactory.getLogger(getClass());
    private static final String START_KEY = "caseStart";

    @Override
    public void beforeTestExecution(ExtensionContext context) {
        getStore(context).put(START_KEY, System.currentTimeMillis());
        log.info("【用例开始】{}#{}",
                context.getTestClass().map(Class::getSimpleName).orElse("?"),
                context.getTestMethod().map(m -> m.getName()).orElse("?"));
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        long cost = System.currentTimeMillis()
                - (Long) getStore(context).get(START_KEY, Long.class);
        boolean failed = context.getExecutionException().isPresent();
        log.info("【用例结果】{}#{} => {}（{} ms）",
                context.getTestClass().map(Class::getSimpleName).orElse("?"),
                context.getTestMethod().map(m -> m.getName()).orElse("?"),
                failed ? "FAIL" : "PASS", cost);
    }

    private ExtensionContext.Store getStore(ExtensionContext context) {
        return context.getStore(ExtensionContext.Namespace.create(getClass(), context.getRequiredTestMethod()));
    }
}
