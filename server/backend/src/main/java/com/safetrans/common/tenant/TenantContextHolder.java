package com.safetrans.common.tenant;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 租户上下文，ThreadLocal 存储。请求进入时由 AuthInterceptor 写入，afterCompletion 清理。
 */
public class TenantContextHolder {

    private static final ThreadLocal<Context> HOLDER = new ThreadLocal<>();

    public static void set(Context ctx) {
        HOLDER.set(ctx);
    }

    public static Context get() {
        return HOLDER.get();
    }

    /** 当前租户 ID；未登录（无上下文）返回 null，表示不注入租户条件 */
    public static Long tenantId() {
        Context ctx = HOLDER.get();
        return ctx == null ? null : ctx.getTenantId();
    }

    public static Long userId() {
        Context ctx = HOLDER.get();
        return ctx == null ? null : ctx.getUserId();
    }

    public static String userType() {
        Context ctx = HOLDER.get();
        return ctx == null ? null : ctx.getUserType();
    }

    /** 是否平台级账号（服务商 provider / 超管 admin），需要跨租户查询 */
    public static boolean isPlatform() {
        String ut = userType();
        return "provider".equals(ut) || "admin".equals(ut);
    }

    public static void clear() {
        HOLDER.remove();
    }

    /**
     * 请求上下文：租户、用户、角色类型。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Context {
        private Long tenantId;
        private Long userId;
        private String username;
        /** enterprise / provider / admin */
        private String userType;
    }
}
