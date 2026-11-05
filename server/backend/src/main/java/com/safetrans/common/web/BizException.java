package com.safetrans.common.web;

/**
 * 业务异常，携带错误码与提示信息，由全局异常处理器统一转 Result。
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(String message) {
        this(1, message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static BizException of(String message) {
        return new BizException(message);
    }
}
