package com.safetrans.common.auth;

import com.safetrans.common.web.BizException;
import com.safetrans.common.web.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;

/**
 * 轻量限流（A3）：基于 Redis 的固定窗口计数器，保护登录/验证码等匿名接口。
 *
 * <p>按「客户端 IP + 具体端点」分桶，而不是同一 IP 下所有 /api/auth/** 共享一个配额。
 * 原因：一次登录需要 captcha + login 两次请求，全局配额下刷新验证码会挤占登录额度；
 * 企业办公室多人共享同一出口 IP 时，一人的验证码刷新就可能让其他人登录失败（429）。
 * 分桶后每个端点仍独立受 max/window 约束，对 login 的暴力破解防护强度不变。
 *
 * <p>默认 60 秒内同 IP 单端点最多 60 次，可由 safetrans.rate-limit.* 覆盖。
 * 阈值按「企业办公室共享同一出口 IP、早高峰集中登录」的场景设定；
 * 若部署在独立 IP 或需要更严防刷，可通过 RATE_LIMIT_MAX 下调。
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate redis;
    private final long max;
    private final long windowSeconds;

    public RateLimitInterceptor(StringRedisTemplate redis,
                                @Value("${safetrans.rate-limit.max:60}") long max,
                                @Value("${safetrans.rate-limit.window-seconds:60}") long windowSeconds) {
        this.redis = redis;
        this.max = max;
        this.windowSeconds = windowSeconds;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String uri = request.getRequestURI();
        if (!uri.startsWith("/api/auth/")) return true;
        String ip = clientIp(request);
        String key = "rate:auth:" + ip + ":" + uri;
        Long n;
        try {
            n = redis.opsForValue().increment(key);
            if (n != null && n == 1L) redis.expire(key, Duration.ofSeconds(windowSeconds));
        } catch (Exception e) {
            return true; // Redis 不可用时降级放行，不阻塞登录
        }
        if (n != null && n > max) throw ErrorCode.RATE_LIMITED.ex();
        return true;
    }

    private String clientIp(HttpServletRequest req) {
        String xf = req.getHeader("X-Forwarded-For");
        if (xf != null && !xf.isBlank()) return xf.split(",")[0].trim();
        return req.getRemoteAddr() == null ? "unknown" : req.getRemoteAddr();
    }
}
