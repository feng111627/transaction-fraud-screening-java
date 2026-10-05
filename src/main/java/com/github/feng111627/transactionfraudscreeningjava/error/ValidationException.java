package com.github.feng111627.transactionfraudscreeningjava.error;

/** 字段缺失/非法等校验失败 */
public class ValidationException extends ApiException {
    public ValidationException(String code, String message) {
        super(code, message);
    }
}
