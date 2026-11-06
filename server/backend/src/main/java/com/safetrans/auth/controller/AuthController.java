package com.safetrans.auth.controller;

import com.safetrans.auth.dto.CaptchaResp;
import com.safetrans.auth.dto.LoginReq;
import com.safetrans.auth.dto.LoginResp;
import com.safetrans.auth.service.AuthService;
import com.safetrans.common.auth.Anonymous;
import com.safetrans.common.tenant.TenantContextHolder;
import com.safetrans.common.web.BizException;
import com.safetrans.common.web.Result;
import org.springframework.beans.factory.annotation.Value;
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

    /**
     * 开发环境免验证码登录：仅当 app.dev-login-enabled=true 时可用，生产环境（prod profile）强制关闭。
     * 用于前端 AUTO_LOGIN 模式跳过登录界面直接进系统，提升逐项测试效率。
     */
    @Value("${app.dev-login-enabled:false}")
    private boolean devLoginEnabled;

    @Anonymous
    @PostMapping("/dev-login")
    public Result<LoginResp> devLogin(@RequestBody LoginReq req) {
        if (!devLoginEnabled) {
            throw new BizException(403, "开发免密登录已关闭");
        }
        return Result.ok(authService.devLogin(req));
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
