package com.unirag.upload.support;

import lombok.Getter;

/**
 * 统一成功响应包装（contracts §1）：{@code { "data": ... }}。
 */
@Getter
public class ApiResponse<T> {

    private final T data;

    private ApiResponse(T data) {
        this.data = data;
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(data);
    }
}
