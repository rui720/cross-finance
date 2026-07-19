package com.finance.platform.common.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * JWT 生成与解析工具
 */
@Component
public class JwtUtils {

    @Value("${finance.jwt.secret}")
    private String secret;

    @Value("${finance.jwt.expire}")
    private long expire;

    @Value("${finance.jwt.header}")
    private String header;

    @Value("${finance.jwt.prefix}")
    private String prefix;

    public String getHeader() {
        return header;
    }

    public String getPrefix() {
        return prefix;
    }

    private SecretKey key() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成 token
     *
     * @param userId 用户ID
     * @param username 用户名
     * @param roles 角色列表
     */
    public String generate(Long userId, String username, List<String> roles) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + expire * 1000);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("roles", roles)
                .issuedAt(now)
                .expiration(expiration)
                .signWith(key())
                .compact();
    }

    /**
     * 解析 token
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 校验 token 是否有效
     */
    public boolean isValid(String token) {
        try {
            Claims claims = parse(token);
            return claims.getExpiration().after(new Date());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 从 token 提取用户ID
     */
    public Long getUserId(String token) {
        return Long.valueOf(parse(token).getSubject());
    }

    /**
     * 从 token 提取角色列表
     */
    @SuppressWarnings("unchecked")
    public List<String> getRoles(String token) {
        Object roles = parse(token).get("roles");
        if (roles instanceof List<?> list) {
            return (List<String>) list;
        }
        return List.of();
    }

    /**
     * 构建响应头
     */
    public Map<String, String> headerMap(String token) {
        return Map.of(header, prefix + token);
    }
}
