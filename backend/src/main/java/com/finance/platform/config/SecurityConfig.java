package com.finance.platform.config;

import com.finance.platform.common.utils.JwtUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.HashMap;
import java.util.Map;

/**
 * Spring Security + JWT 安全配置
 * <p>
 * 采用无状态会话，所有请求通过 JWT 校验。
 * 白名单：登录/注册/Swagger/健康检查/OPTIONS 预检。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    private final JwtUtils jwtUtils;

    public SecurityConfig(JwtUtils jwtUtils) {
        this.jwtUtils = jwtUtils;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 关闭 CSRF（无状态接口无需 CSRF 保护）
                .csrf(csrf -> csrf.disable())
                // 跨域交给 WebMvcConfig 统一处理，此处启用 Spring Security 侧 CORS
                .cors(cors -> {})
                // 无状态会话
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 预检放行
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // 白名单接口
                        .requestMatchers(
                                "/auth/login",
                                "/auth/register",
                                "/actuator/health",
                                "/doc.html",
                                "/swagger-ui/**",
                                "/v3/api-docs/**"
                        ).permitAll()
                        // 其余接口需认证
                        .anyRequest().authenticated()
                )
                // 异常处理：未认证（JWT 过期/缺失）返回 401，权限不足返回 403
                // 区分两种语义，前端据此分别跳登录页或显示无权限
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(401);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding("UTF-8");
                            Map<String, Object> body = new HashMap<>();
                            body.put("code", 401);
                            body.put("msg", "登录已失效，请重新登录");
                            body.put("data", null);
                            response.getWriter().write(new ObjectMapper().writeValueAsString(body));
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setStatus(403);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding("UTF-8");
                            Map<String, Object> body = new HashMap<>();
                            body.put("code", 403);
                            body.put("msg", "权限不足，无法访问");
                            body.put("data", null);
                            response.getWriter().write(new ObjectMapper().writeValueAsString(body));
                        })
                )
                // JWT 过滤器置于用户名密码过滤器之前
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtUtils),
                        UsernamePasswordAuthenticationFilter.class
                );
        return http.build();
    }
}
