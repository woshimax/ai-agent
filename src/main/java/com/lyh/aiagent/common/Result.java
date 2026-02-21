package com.lyh.aiagent.common;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class Result<T> {

    private int code;
    private T data;
    private String message;

    public static <T> Result<T> success(T data) {
        return new Result<>(0, data, "ok");
    }

    public static Result<?> error(int code, String message) {
        return new Result<>(code, null, message);
    }
}
