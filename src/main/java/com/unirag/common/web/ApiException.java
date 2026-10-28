package com.unirag.common.web;

import lombok.Getter;

/**
 * 业务异常：携带契约错误码（contracts §1）。message 必须人类可读且不含密钥/config 原文（FR-010）。
 */
@Getter
public class ApiException extends RuntimeException {

    private final String code;
    private final Object details;

    public ApiException(String code, String message) {
        this(code, message, null);
    }

    public ApiException(String code, String message, Object details) {
        super(message);
        this.code = code;
        this.details = details;
    }
}
