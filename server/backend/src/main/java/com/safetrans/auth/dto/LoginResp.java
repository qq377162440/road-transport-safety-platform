package com.safetrans.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录响应：token + 用户信息。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginResp {
    private String accessToken;
    private String refreshToken;
    private UserInfo userInfo;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserInfo {
        private Long userId;
        private Long tenantId;
        private String username;
        private String realName;
        private String userType;
    }
}
