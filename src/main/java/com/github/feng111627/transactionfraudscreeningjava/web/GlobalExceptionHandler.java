package com.github.feng111627.transactionfraudscreeningjava.web;

import com.github.feng111627.transactionfraudscreeningjava.error.AccessDeniedException;
import com.github.feng111627.transactionfraudscreeningjava.error.ApiException;
import com.github.feng111627.transactionfraudscreeningjava.error.ConflictException;
import com.github.feng111627.transactionfraudscreeningjava.error.NotFoundException;
import com.github.feng111627.transactionfraudscreeningjava.error.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 业务异常 -> HTTP 状态码与稳定错误码，调用方可以按 code 区分拒绝原因。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(ValidationException e) {
        return build(HttpStatus.BAD_REQUEST, e);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(ConflictException e) {
        return build(HttpStatus.CONFLICT, e);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NotFoundException e) {
        return build(HttpStatus.NOT_FOUND, e);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleDenied(AccessDeniedException e) {
        return build(HttpStatus.FORBIDDEN, e);
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, ApiException e) {
        log.warn("请求被拒绝 status={} code={} message={}", status.value(), e.getCode(), e.getMessage());
        return ResponseEntity.status(status).body(Map.of(
                "code", e.getCode(),
                "message", e.getMessage() == null ? "" : e.getMessage()));
    }
}
