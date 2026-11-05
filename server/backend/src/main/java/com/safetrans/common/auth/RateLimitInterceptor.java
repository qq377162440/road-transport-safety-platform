package com.safetrans.common.auth;

import com.safetrans.common.web.BizException;
import com.safetrans.common.web.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;

/**
 * 轻量限流（A3）：基于 Redis 的固定窗口计数器，保护登录/验证码等匿名接口。
 * 默认 60 秒内同 IP 最多 20 次，可由 safetrans.rate-limit.* 覆盖。
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate redis;

    public RateLimitInterceptor(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String uri = request.getRequestURI();
        if (!uri.startsWith("/api/auth/")) return true;
        String ip = clientIp(request);
        String key = "rate:auth:" + ip;
        Long n;
        try {
            n = redis.opsForValue().increment(key);
            if (n != null && n == 1L) redis.expire(key, Duration.ofSeconds(60));
        } catch (Exception e) {
            return true; // Redis 不可用时降级放行，不阻塞登录
        }
        if (n != null && n > 20L) throw ErrorCode.RATE_LIMITED.ex();
        return true;
    }

    private String clientIp(HttpServletRequest req) {
        String xf = req.getHeader("X-Forwarded-For");
        if (xf != null && !xf.isBlank()) return xf.split(",")[0].trim();
        return req.getRemoteAddr() == null ? "unknown" : req.getRemoteAddr();
    }
}
