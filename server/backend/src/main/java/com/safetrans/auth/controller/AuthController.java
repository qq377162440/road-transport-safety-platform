package com.safetrans.auth.controller;

import com.safetrans.auth.dto.CaptchaResp;
import com.safetrans.auth.dto.LoginReq;
import com.safetrans.auth.dto.LoginResp;
import com.safetrans.auth.service.AuthService;
import com.safetrans.common.auth.Anonymous;
import com.safetrans.common.tenant.TenantContextHolder;
import com.safetrans.common.web.Result;
import org.springframework.web.bind.annotation.*;

/**
 * 认证接口。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Anonymous
    @GetMapping("/captcha")
    public Result<CaptchaResp> captcha() {
        return Result.ok(authService.captcha());
    }

    @Anonymous
    @PostMapping("/login")
    public Result<LoginResp> login(@RequestBody LoginReq req) {
        return Result.ok(authService.login(req));
    }

    @PostMapping("/logout")
    public Result<Void> logout() {
        Long uid = TenantContextHolder.userId();
        if (uid != null) {
            authService.logout(uid);
        }
        return Result.ok();
    }

    @GetMapping("/me")
    public Result<TenantContextHolder.Context> me() {
        return Result.ok(TenantContextHolder.get());
    }
}
