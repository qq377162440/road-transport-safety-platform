package com.safetrans;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 道路运输安全生产管理平台 · 后端启动类
 * 单体模块化，按业务域分包（common/auth/tenant/org/tpl/archive）。
 */
@SpringBootApplication
@MapperScan("com.safetrans.**.mapper")
public class SafetyTransApplication {
    public static void main(String[] args) {
        SpringApplication.run(SafetyTransApplication.class, args);
    }
}
