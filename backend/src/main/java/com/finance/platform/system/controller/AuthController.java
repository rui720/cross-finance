package com.finance.platform.system.controller;

import com.finance.platform.common.core.Result;
import com.finance.platform.common.utils.JwtUtils;
import com.finance.platform.system.service.SysUserService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 认证接口：登录/登出/刷新 Token
 * <p>
 * /auth/login、/auth/refresh 路径在 SecurityConfig 中已加入白名单，无需 accessToken 即可访问。
 * 其中 /auth/refresh 需携带有效的 refreshToken。
 */
@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final SysUserService sysUserService;
    private final JwtUtils jwtUtils;

    /**
     * 用户登录（双 Token）
     * <p>
     * 请求体：{ "username": "your_username", "password": "your_password" }<br>
     * 返回：{ "accessToken": "xxx", "refreshToken": "yyy", "user": { id, username, realName, ... } }
     * <p>
     * accessToken 有效期 2 小时，用于业务接口；refreshToken 有效期 7 天，仅用于 /auth/refresh 续期。
     */
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody LoginDTO dto) {
        SysUserService.LoginResult result = sysUserService.loginWithRefresh(dto.username(), dto.password());
        Map<String, Object> data = new HashMap<>(3);
        data.put("accessToken", result.accessToken());
        data.put("refreshToken", result.refreshToken());
        // 兼容前端旧字段 token（部分页面可能仍读 token）
        data.put("token", result.accessToken());
        data.put("user", result.user());
        log.info("[Auth] 登录成功 username={}", dto.username());
        return Result.success(data);
    }

    /**
     * 刷新 accessToken
     * <p>
     * 当 accessToken 过期（业务接口返回 401）时，前端应使用 refreshToken 调用本接口换取新的 accessToken + refreshToken。
     * refreshToken 同时轮换（旧的失效），降低被窃取后的风险。
     * <p>
     * 请求体：{ "refreshToken": "yyy" }<br>
     * 返回：{ "accessToken": "新xxx", "refreshToken": "新yyy", "user": {...} }
     */
    @PostMapping("/refresh")
    public Result<Map<String, Object>> refresh(@RequestBody RefreshDTO dto) {
        SysUserService.LoginResult result = sysUserService.refresh(dto.refreshToken());
        Map<String, Object> data = new HashMap<>(3);
        data.put("accessToken", result.accessToken());
        data.put("refreshToken", result.refreshToken());
        data.put("token", result.accessToken());
        data.put("user", result.user());
        return Result.success(data);
    }

    /**
     * 登出（前端清除本地 token 即可，后端无状态无需处理）
     */
    @PostMapping("/logout")
    public Result<Void> logout() {
        return Result.success();
    }

    /**
     * 登录请求参数
     */
    public record LoginDTO(@NotBlank(message = "用户名不能为空") String username,
                           @NotBlank(message = "密码不能为空") String password) {
    }

    /**
     * 刷新 Token 请求参数
     */
    public record RefreshDTO(@NotBlank(message = "refreshToken 不能为空") String refreshToken) {
    }
}
