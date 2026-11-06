package com.safetrans.auth.service;

import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.CircleCaptcha;
import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.safetrans.auth.dto.CaptchaResp;
import com.safetrans.auth.dto.LoginReq;
import com.safetrans.auth.dto.LoginResp;
import com.safetrans.auth.entity.SysUser;
import com.safetrans.auth.mapper.SysUserMapper;
import com.safetrans.common.auth.AuthInterceptor;
import com.safetrans.common.auth.JwtUtil;
import com.safetrans.common.web.BizException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 认证服务：验证码、登录、登出、刷新、当前用户。
 */
@Service
public class AuthService {

    private static final String CAPTCHA_KEY = "captcha:";

    private final SysUserMapper userMapper;
    private final StringRedisTemplate redis;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthService(SysUserMapper userMapper, StringRedisTemplate redis,
                       PasswordEncoder passwordEncoder, JwtUtil jwtUtil) {
        this.userMapper = userMapper;
        this.redis = redis;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    /** 生成图形验证码，存 Redis（5 分钟，一次性） */
    public CaptchaResp captcha() {
        CircleCaptcha captcha = CaptchaUtil.createCircleCaptcha(120, 40, 4, 20);
        String code = captcha.getCode();
        String uuid = IdUtil.fastSimpleUUID();
        redis.opsForValue().set(CAPTCHA_KEY + uuid, code, Duration.ofMinutes(5));
        return new CaptchaResp(uuid, captcha.getImageBase64());
    }

    public LoginResp login(LoginReq req) {
        // 校验验证码（一次性）
        String code = redis.opsForValue().get(CAPTCHA_KEY + req.getCaptchaId());
        if (code == null) {
            throw new BizException("验证码已过期，请刷新重试");
        }
        if (!code.equalsIgnoreCase(req.getCaptcha())) {
            throw new BizException("验证码错误");
        }
        redis.delete(CAPTCHA_KEY + req.getCaptchaId());

        // 查用户
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, req.getUsername()));
        if (user == null || !passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            throw new BizException("账号或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BizException("账号已禁用");
        }

        // 身份以数据库 user_type 为准（E2 开户生成的账号不遵循 aa/bb/cc 前缀，
        // 且人员手机号开通的 driver 账号需要按岗位差异化）；老数据无 user_type
        // 时回退到账号前缀识别，保证向后兼容。
        String userType = user.getUserType();
        if (userType == null || userType.isBlank()) {
            userType = detectUserType(user.getUsername());
        }

        String access = jwtUtil.genAccessToken(user.getId(), user.getTenantId(), user.getUsername(), userType);
        String refresh = jwtUtil.genRefreshToken(user.getId(), user.getTenantId(), user.getUsername(), userType);
        // 白名单（access token）
        redis.opsForValue().set(AuthInterceptor.WHITELIST_KEY + user.getId(), access, Duration.ofSeconds(7200));

        LoginResp.UserInfo info = new LoginResp.UserInfo(
                user.getId(), user.getTenantId(), user.getUsername(), user.getRealName(), userType);
        return new LoginResp(access, refresh, info);
    }

    /** A3：用 refresh token 换取新的 access token；refresh 不可当 access 使用 */
    public LoginResp refresh(String refreshToken) {
        io.jsonwebtoken.Claims c;
        try {
            c = jwtUtil.parse(refreshToken);
        } catch (Exception e) {
            throw new BizException(401, "刷新令牌无效，请重新登录");
        }
        if (!"refresh".equals(c.get("typ", String.class))) {
            throw new BizException(401, "请使用 refresh token 刷新");
        }
        Long userId = Long.valueOf(c.getSubject());
        Long tenantId = c.get("tenantId", Long.class);
        String username = c.get("username", String.class);
        String userType = c.get("userType", String.class);
        SysUser user = userMapper.selectById(userId);
        if (user == null || (user.getStatus() != null && user.getStatus() == 0)) {
            throw new BizException(401, "账号不可用，请重新登录");
        }
        String access = jwtUtil.genAccessToken(userId, tenantId, username, userType);
        redis.opsForValue().set(AuthInterceptor.WHITELIST_KEY + userId, access, Duration.ofSeconds(7200));
        LoginResp.UserInfo info = new LoginResp.UserInfo(
                userId, tenantId, username, user.getRealName(), userType);
        return new LoginResp(access, refreshToken, info);
    }

    /** A3：修改密码（校验旧密码 + 强度策略：≥8 位且含字母与数字） */
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        if (newPassword == null || !newPassword.matches("^(?=.*[A-Za-z])(?=.*\\d).{8,}$")) {
            throw new BizException(400, "新密码需至少 8 位，且同时包含字母和数字");
        }
        SysUser user = userMapper.selectById(userId);
        if (user == null) throw new BizException(404, "用户不存在");
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new BizException(400, "原密码不正确");
        }
        SysUser upd = new SysUser();
        upd.setId(userId);
        upd.setPassword(passwordEncoder.encode(newPassword));
        userMapper.updateById(upd);
        // 密码变更后强制旧 token 失效
        redis.delete(AuthInterceptor.WHITELIST_KEY + userId);
    }

    public void logout(Long userId) {
        redis.delete(AuthInterceptor.WHITELIST_KEY + userId);
    }

    private String detectUserType(String username) {
        String u = username.toLowerCase();
        if (u.startsWith("aa")) return "enterprise";
        if (u.startsWith("bb")) return "provider";
        if (u.startsWith("cc")) return "admin";
        return "enterprise";
    }
}
