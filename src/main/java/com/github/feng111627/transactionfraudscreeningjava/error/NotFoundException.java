package com.github.feng111627.transactionfraudscreeningjava.error;

/** 资源不存在 */
public class NotFoundException extends ApiException {
    public NotFoundException(String code, String message) {
        super(code, message);
    }
}
