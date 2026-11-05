package com.safetrans.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 验证码响应：uuid + base64 图片。
 */
@Data
@AllArgsConstructor
public class CaptchaResp {
    private String captchaId;
    private String base64Image;
}
