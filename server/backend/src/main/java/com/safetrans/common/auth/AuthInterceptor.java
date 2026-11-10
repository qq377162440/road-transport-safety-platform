package com.safetrans.common.auth;

import com.safetrans.common.tenant.TenantContextHolder;
import com.safetrans.common.web.BizException;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.Set;

/**
 * 认证拦截器：解析 JWT → 校验 Redis 白名单 → 写租户上下文 → 角色鉴权 → afterCompletion 清理 ThreadLocal。
 *
 * <p>平台账号（服务商 / 超管）默认 tenant_id=0 跨租户。若请求带 X-Target-Tenant，
 * 则把租户上下文切到该目标企业：下游 TplSetScope / SealService / TplPersonResolver /
 * ArchiveService 会按目标企业读取当前应用范本套、公司章、岗位人名，并归档到该企业的档案柜。
 * 该能力用于「范本定制与生成为服务商 / 超管增值服务」——平台账号代指定企业生成台账成品。
 *
 * <p>所有可能抛异常的鉴权都在写 ThreadLocal 之前完成，避免 preHandle 抛异常时
 * afterCompletion 不被调用而残留租户上下文（线程池复用会串租户）。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String AUTH_HEADER = "Authorization";
    public static final String ALT_AUTH_HEADER = "X-Token";
    public static final String TOKEN_PREFIX = "Bearer ";
    public static final String WHITELIST_KEY = "auth:token:";
    /** 平台账号指定「当前服务企业」的请求头 */
    public static final String TARGET_TENANT_HEADER = "X-Target-Tenant";
    /** 平台账号必须先指定目标企业才允许执行的生成类接口 */
    private static final Set<String> GENERATE_PATHS = Set.of(
            "/api/archive/template/generate",
            "/api/archive/responsibility/generate");

    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;

    public AuthInterceptor(JwtUtil jwtUtil, StringRedisTemplate redis, JdbcTemplate jdbc) {
        this.jwtUtil = jwtUtil;
        this.redis = redis;
        this.jdbc = jdbc;
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

        Long tenantId = claims.get("tenantId", Long.class);
        String username = claims.get("username", String.class);
        String userType = claims.get("userType", String.class);
        boolean platform = "provider".equals(userType) || "admin".equals(userType);
        Long effectiveTenant = platform ? resolveTargetTenant(request, tenantId) : tenantId;

        // 平台账号未选企业时 tenantId=0，直接生成会产出无范本、无人名、无章且落到 0 号租户的废品
        if (platform && GENERATE_PATHS.contains(request.getRequestURI())
                && (effectiveTenant == null || effectiveTenant == 0L)) {
            throw new BizException(403, "请先在右上角选择要生成台账的企业");
        }

        // 角色鉴权（用 JWT 原始角色，不受目标租户切换影响）
        checkRole(hm, userType);

        // 写租户上下文：平台账号选定目标企业后，tenant_id 为目标企业，userType 仍为 provider/admin
        TenantContextHolder.set(new TenantContextHolder.Context(effectiveTenant, userId, username, userType));

        return true;
    }

    /**
     * 仅平台账号生效：校验目标企业存在且启用，返回其 id；未指定 / 非法时保持原租户（0，跨租户）。
     * 企业账号即使伪造该请求头也会被忽略，无法越权访问其他企业数据。
     */
    private Long resolveTargetTenant(HttpServletRequest request, Long original) {
        String raw = request.getHeader(TARGET_TENANT_HEADER);
        if (raw == null || raw.isBlank()) return original;
        long id;
        try {
            id = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return original;
        }
        if (id <= 0L) return original;
        try {
            Integer ok = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM `sys_tenant` WHERE `id`=? AND `deleted`=0 AND `status`=1",
                    Integer.class, id);
            return (ok != null && ok > 0) ? id : original;
        } catch (Exception e) {
            return original; // 校验异常时保持跨租户，不阻断平台侧查询
        }
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
        // 自定义头优先：部分托管反向代理会改写 Authorization 为其自身会话凭证
        String alt = request.getHeader(ALT_AUTH_HEADER);
        if (alt != null && alt.startsWith(TOKEN_PREFIX)) {
            return alt.substring(TOKEN_PREFIX.length());
        }
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
