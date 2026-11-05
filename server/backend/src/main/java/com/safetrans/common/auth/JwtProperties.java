package com.safetrans.common.auth;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置属性。
 */
@Data
@Component
@ConfigurationProperties(prefix = "safetrans.jwt")
public class JwtProperties {
    private String secret;
    private long accessTokenTtl = 7200;
    private long refreshTokenTtl = 604800;
}
