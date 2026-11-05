package com.safetrans.auth.dto;

import lombok.Data;

/**
 * 登录请求。
 */
@Data
public class LoginReq {
    private String username;
    private String password;
    /** 验证码 uuid */
    private String captchaId;
    /** 验证码 */
    private String captcha;
}
