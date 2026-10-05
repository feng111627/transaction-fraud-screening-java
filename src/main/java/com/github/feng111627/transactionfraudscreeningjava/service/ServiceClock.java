package com.github.feng111627.transactionfraudscreeningjava.service;

import org.springframework.stereotype.Component;

/** 统一时钟，便于测试中固定时间 */
@Component
public class ServiceClock {
    public long nowMillis() {
        return System.currentTimeMillis();
    }
}
