package com.safetrans.common.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口级角色鉴权注解。标注在 Controller 方法或类上，
 * 由 AuthInterceptor 校验当前用户 userType 是否在允许列表内。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {
    /** 允许访问的角色，如 {"provider", "admin"} */
    String[] value();
}
