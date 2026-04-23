package com.example.coa.common;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final int code;
    private final List<Map<String, Object>> errors;

    public ApiException(HttpStatus status, int code, String message) {
        this(status, code, message, List.of());
    }

    public ApiException(HttpStatus status, int code, String message, List<Map<String, Object>> errors) {
        super(message);
        this.status = status;
        this.code = code;
        this.errors = errors;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public int getCode() {
        return code;
    }

    public List<Map<String, Object>> getErrors() {
        return errors;
    }
}
