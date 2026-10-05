package com.github.feng111627.transactionfraudscreeningjava.error;

/** 越权操作 */
public class AccessDeniedException extends ApiException {
    public AccessDeniedException(String message) {
        super("ACCESS_DENIED", message);
    }
}
