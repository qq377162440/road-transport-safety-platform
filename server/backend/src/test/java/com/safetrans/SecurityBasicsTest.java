package com.safetrans;

import com.safetrans.common.auth.JwtProperties;
import com.safetrans.common.auth.JwtUtil;
import com.safetrans.common.web.ErrorCode;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A3 单元测试：JWT 类型隔离、错误码唯一性、密码策略。
 */
class SecurityBasicsTest {

    private JwtUtil jwtUtil() {
        JwtProperties p = new JwtProperties();
        p.setSecret("unit-test-secret-key-please-change-0123456789-abcdefgh");
        p.setAccessTokenTtl(7200);
        p.setRefreshTokenTtl(604800);
        return new JwtUtil(p);
    }

    @Test
    void accessAndRefreshTokensShouldCarryDifferentTypes() {
        JwtUtil jwt = jwtUtil();
        String access = jwt.genAccessToken(1L, 5001L, "aa_gaozhou", "enterprise");
        String refresh = jwt.genRefreshToken(1L, 5001L, "aa_gaozhou", "enterprise");

        Claims a = jwt.parse(access);
        Claims r = jwt.parse(refresh);
        assertEquals("access", a.get("typ", String.class));
        assertEquals("refresh", r.get("typ", String.class));
        assertEquals("1", a.getSubject());
        assertEquals(5001L, a.get("tenantId", Long.class));
        assertNotEquals(access, refresh);
    }

    @Test
    void tamperedTokenShouldBeRejected() {
        JwtUtil jwt = jwtUtil();
        String token = jwt.genAccessToken(1L, 5001L, "aa_gaozhou", "enterprise");
        String tampered = token.substring(0, token.length() - 4) + "AAAA";
        assertThrows(Exception.class, () -> jwt.parse(tampered));
    }

    @Test
    void errorCodesShouldBeUniqueAndGrouped() {
        Set<Integer> codes = new HashSet<>();
        for (ErrorCode e : ErrorCode.values()) {
            assertTrue(codes.add(e.code()), "错误码重复：" + e.code());
            assertTrue(e.code() >= 0 && e.code() < 6000);
        }
        assertEquals(401, ErrorCode.UNAUTHORIZED.code());
        assertEquals(403, ErrorCode.FORBIDDEN.code());
        assertEquals(429, ErrorCode.RATE_LIMITED.code());
    }

    @Test
    void passwordPolicyShouldRejectWeakPasswords() {
        String policy = "^(?=.*[A-Za-z])(?=.*\\d).{8,}$";
        assertTrue("safety2026".matches(policy));
        assertFalse("123456".matches(policy));
        assertFalse("password".matches(policy));
        assertFalse("abc123".matches(policy));
    }
}
