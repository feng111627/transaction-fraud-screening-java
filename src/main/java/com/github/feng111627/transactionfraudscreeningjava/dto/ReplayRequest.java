package com.github.feng111627.transactionfraudscreeningjava.dto;

/**
 * 决策重算请求。重算是只读核对操作，operator 仅用于审计留痕。
 */
public record ReplayRequest(String operator) {
}
