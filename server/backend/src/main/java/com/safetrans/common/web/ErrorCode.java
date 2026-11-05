package com.safetrans.common.web;

/**
 * 统一业务错误码（A2）。
 * 1000~1999 认证授权，2000~2999 参数/校验，3000~3999 业务规则，4000~4999 系统/依赖。
 */
public enum ErrorCode {

    OK(0, "ok"),
    PARAM_INVALID(400, "参数校验失败"),
    UNAUTHORIZED(401, "未登录或登录已过期"),
    FORBIDDEN(403, "无权限访问该资源"),
    NOT_FOUND(404, "资源不存在"),
    RATE_LIMITED(429, "请求过于频繁，请稍后再试"),
    SYS_ERROR(500, "系统繁忙，请稍后重试"),

    CAPTCHA_INVALID(1001, "验证码错误或已过期"),
    CREDENTIAL_INVALID(1002, "账号或密码错误"),
    TOKEN_INVALID(1003, "登录凭证无效"),
    PASSWORD_WEAK(1004, "密码强度不足"),

    FIELD_REQUIRED(2001, "必填字段缺失"),
    FIELD_ILLEGAL(2002, "存在非法字段"),
    TABLE_ILLEGAL(2003, "非法业务表"),

    BIZ_NOT_FOUND(3001, "业务记录不存在"),
    BIZ_STATE_INVALID(3002, "当前状态不允许该操作"),
    ARCHIVE_FAILED(3003, "归档登记失败"),

    STORAGE_ERROR(4001, "文件存储服务异常");

    private final int code;
    private final String msg;

    ErrorCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public int code() {
        return code;
    }

    public String msg() {
        return msg;
    }

    public BizException ex() {
        return new BizException(code, msg);
    }

    public BizException ex(String detail) {
        return new BizException(code, msg + "：" + detail);
    }
}
