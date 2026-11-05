package com.safetrans.common.web;

import com.safetrans.common.auth.Anonymous;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 健康检查（自定义 /health，便于冒烟）。
 */
@RestController
public class HealthController {

    @Anonymous
    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        return Result.ok(Map.of(
                "status", "UP",
                "time", LocalDateTime.now().toString(),
                "service", "safetrans-backend"
        ));
    }
}
