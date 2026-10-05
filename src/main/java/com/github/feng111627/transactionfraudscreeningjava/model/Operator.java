package com.github.feng111627.transactionfraudscreeningjava.model;

import java.util.Set;

/** 操作人及其角色 */
public record Operator(String name, Set<Role> roles) {
}
