package com.safetrans.common.audit;

import java.lang.annotation.*;

/**
 * 操作日志注解（A2）。标注在写操作上，由 OperLogAspect 落 sys_oper_log。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OperLog {
    String module() default "biz";
    String action() default "update";
}
