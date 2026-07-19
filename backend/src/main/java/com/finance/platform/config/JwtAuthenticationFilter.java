package com.finance.platform.config;

import com.finance.platform.common.core.LoginUser;
import com.finance.platform.common.utils.JwtUtils;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * JWT 鉴权过滤器：从请求头解析 token，构建认证上下文。
 * <p>
 * token 不存在或非法时不抛异常，交由后续 Security 链处理（白名单放行，其余 401）。
 * principal 使用 LoginUser（同时持有 userId 与 username），便于审计日志与自动填充。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtils jwtUtils;

    public JwtAuthenticationFilter(JwtUtils jwtUtils) {
        this.jwtUtils = jwtUtils;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(jwtUtils.getHeader());
        if (StringUtils.hasText(header) && header.startsWith(jwtUtils.getPrefix())) {
            String token = header.substring(jwtUtils.getPrefix().length());
            try {
                Claims claims = jwtUtils.parse(token);
                Long userId = Long.valueOf(claims.getSubject());
                String username = claims.get("username", String.class);
                Object roles = claims.get("roles");
                // Spring Security 的 hasRole('X') 会自动加 ROLE_ 前缀进行匹配，
                // 因此 authority 必须以 ROLE_ 开头，否则 hasRole 永远匹配失败。
                List<SimpleGrantedAuthority> authorities = (roles instanceof List<?> list)
                        ? list.stream()
                            .map(Object::toString)
                            .map(r -> r.startsWith("ROLE_") ? r : "ROLE_" + r)
                            .map(SimpleGrantedAuthority::new)
                            .collect(Collectors.toList())
                        : List.of();
                LoginUser principal = new LoginUser(userId, username);
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(principal, null, authorities);
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception ignored) {
                // 解析失败，保持未认证状态，由后续链处理
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
