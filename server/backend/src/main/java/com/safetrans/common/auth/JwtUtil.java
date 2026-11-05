package com.safetrans.common.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 生成与解析（HS256）。
 */
@Component
public class JwtUtil {

    private final JwtProperties props;
    private final SecretKey key;

    public JwtUtil(JwtProperties props) {
        this.props = props;
        this.key = Keys.hmacShaKeyFor(props.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    /** 生成 access token，载荷携带 userId/tenantId/username/userType */
    public String genAccessToken(Long userId, Long tenantId, String username, String userType) {
        return build(userId, tenantId, username, userType, props.getAccessTokenTtl());
    }

    public String genRefreshToken(Long userId, Long tenantId, String username, String userType) {
        return build(userId, tenantId, username, userType, props.getRefreshTokenTtl());
    }

    private String build(Long userId, Long tenantId, String username, String userType, long ttlSeconds) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("tenantId", tenantId)
                .claim("username", username)
                .claim("userType", userType)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttlSeconds * 1000))
                .signWith(key)
                .compact();
    }

    /** 解析 token，失败抛异常 */
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
