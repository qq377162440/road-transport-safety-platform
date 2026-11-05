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

        // 账号前缀识别身份：aa=企业 / bb=服务商 / cc=超管
        String userType = detectUserType(user.getUsername());

        String access = jwtUtil.genAccessToken(user.getId(), user.getTenantId(), user.getUsername(), userType);
        String refresh = jwtUtil.genRefreshToken(user.getId(), user.getTenantId(), user.getUsername(), userType);
        // 白名单（access token）
        redis.opsForValue().set(AuthInterceptor.WHITELIST_KEY + user.getId(), access, Duration.ofSeconds(7200));

        LoginResp.UserInfo info = new LoginResp.UserInfo(
                user.getId(), user.getTenantId(), user.getUsername(), user.getRealName(), userType);
        return new LoginResp(access, refresh, info);
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
