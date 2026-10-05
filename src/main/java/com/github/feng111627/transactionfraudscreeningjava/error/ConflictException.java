package com.github.feng111627.transactionfraudscreeningjava.error;

/** 非法状态迁移等冲突 */
public class ConflictException extends ApiException {
    public ConflictException(String code, String message) {
        super(code, message);
    }
}
