package com.safetrans.common.web;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一返回结构。code=0 成功，非 0 失败。
 */
@Data
public class Result<T> implements Serializable {

    private int code;
    private String msg;
    private T data;

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.code = 0;
        r.msg = "ok";
        r.data = data;
        return r;
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> fail(int code, String msg) {
        Result<T> r = new Result<>();
        r.code = code;
        r.msg = msg;
        return r;
    }

    public static <T> Result<T> fail(String msg) {
        return fail(1, msg);
    }
}
