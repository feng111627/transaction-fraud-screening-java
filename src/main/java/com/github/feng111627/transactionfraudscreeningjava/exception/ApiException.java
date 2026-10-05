package com.github.feng111627.transactionfraudscreeningjava.exception;

import org.springframework.http.HttpStatus;

/**
 * 业务异常，携带 HTTP 状态码。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
