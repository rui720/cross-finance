package com.finance.platform.system.controller;

import com.finance.platform.common.core.Result;
import com.finance.platform.common.utils.JwtUtils;
import com.finance.platform.system.entity.SysUser;
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
 * 认证接口：登录/登出
 * <p>
 * 该 Controller 的 /auth/login 路径在 SecurityConfig 中已加入白名单，无需 token 即可访问。
 */
@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final SysUserService sysUserService;
    private final JwtUtils jwtUtils;

    /**
     * 用户登录
     * <p>
     * 请求体：{ "username": "your_username", "password": "your_password" }<br>
     * 返回：{ "token": "xxx", "user": { id, username, realName, ... } }
     */
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody LoginDTO dto) {
        String token = sysUserService.login(dto.username(), dto.password());
        // 登录成功后回查用户信息返回前端（password 字段已 @JsonIgnore 不会序列化）
        SysUser user = sysUserService.lambdaQuery()
                .eq(SysUser::getUsername, dto.username())
                .one();
        Map<String, Object> data = new HashMap<>(2);
        data.put("token", token);
        data.put("user", user);
        log.info("[Auth] 登录成功 username={}", dto.username());
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
}
