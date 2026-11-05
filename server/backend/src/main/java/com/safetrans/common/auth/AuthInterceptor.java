package com.safetrans.common.auth;

import com.safetrans.common.tenant.TenantContextHolder;
import com.safetrans.common.web.BizException;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;

/**
 * 认证拦截器：解析 JWT → 校验 Redis 白名单 → 写租户上下文 → 角色鉴权 → afterCompletion 清理 ThreadLocal。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String AUTH_HEADER = "Authorization";
    public static final String TOKEN_PREFIX = "Bearer ";
    public static final String WHITELIST_KEY = "auth:token:";

    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redis;

    public AuthInterceptor(JwtUtil jwtUtil, StringRedisTemplate redis) {
        this.jwtUtil = jwtUtil;
        this.redis = redis;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 非 Controller 方法直接放行
        if (!(handler instanceof HandlerMethod hm)) {
            return true;
        }
        // 允许匿名（登录/验证码接口用 @Anonymous 标注）
        if (hm.hasMethodAnnotation(Anonymous.class) || hm.getBeanType().isAnnotationPresent(Anonymous.class)) {
            return true;
        }

        String token = resolveToken(request);
        if (token == null) {
            throw new BizException(401, "未登录或登录已过期");
        }

        Claims claims;
        try {
            claims = jwtUtil.parse(token);
        } catch (Exception e) {
            throw new BizException(401, "登录凭证无效，请重新登录");
        }

        Long userId = Long.valueOf(claims.getSubject());
        String cached = redis.opsForValue().get(WHITELIST_KEY + userId);
        if (cached == null || !cached.equals(token)) {
            throw new BizException(401, "登录已失效，请重新登录");
        }

        // 写入租户上下文
        Long tenantId = claims.get("tenantId", Long.class);
        String username = claims.get("username", String.class);
        String userType = claims.get("userType", String.class);
        TenantContextHolder.set(new TenantContextHolder.Context(tenantId, userId, username, userType));

        // 角色鉴权
        checkRole(hm, userType);

        return true;
    }

    private void checkRole(HandlerMethod hm, String userType) {
        RequireRole rr = hm.getMethodAnnotation(RequireRole.class);
        if (rr == null) {
            rr = hm.getBeanType().getAnnotation(RequireRole.class);
        }
        if (rr == null) {
            return; // 未标注则仅要求登录
        }
        boolean allowed = Arrays.asList(rr.value()).contains(userType);
        if (!allowed) {
            throw new BizException(403, "无权限访问该资源");
        }
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(AUTH_HEADER);
        if (header != null && header.startsWith(TOKEN_PREFIX)) {
            return header.substring(TOKEN_PREFIX.length());
        }
        return null;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // 关键：请求结束清理 ThreadLocal，防线程池串租户
        TenantContextHolder.clear();
    }
}
