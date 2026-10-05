package com.github.feng111627.transactionfraudscreeningjava.error;

/** 业务异常基类，携带稳定的错误码 */
public class ApiException extends RuntimeException {
    private final String code;

    public ApiException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
