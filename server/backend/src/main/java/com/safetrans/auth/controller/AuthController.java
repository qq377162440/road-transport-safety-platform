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

    /** A3：刷新 access token（匿名，凭 refresh token） */
    @Anonymous
    @PostMapping("/refresh")
    public Result<LoginResp> refresh(@RequestBody java.util.Map<String, String> body) {
        return Result.ok(authService.refresh(body.get("refreshToken")));
    }

    /** A3：修改密码（需登录），成功后旧 token 失效需重新登录 */
    @PostMapping("/change-password")
    public Result<Void> changePassword(@RequestBody java.util.Map<String, String> body) {
        Long uid = TenantContextHolder.userId();
        if (uid == null) throw new com.safetrans.common.web.BizException(401, "未登录");
        authService.changePassword(uid, body.get("oldPassword"), body.get("newPassword"));
        return Result.ok();
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
