package com.finance.platform.common.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * JWT 生成与解析工具
 * <p>
 * 双 Token 机制：
 * <ul>
 *   <li>accessToken：短期（默认 2 小时），用于业务接口鉴权</li>
 *   <li>refreshToken：长期（默认 7 天），仅用于 /auth/refresh 续期接口，不可用于业务接口</li>
 * </ul>
 * 两种 Token 均携带 type 声明（access / refresh），解析时通过 type 区分用途，
 * 防止 refreshToken 被误用作业务接口的 accessToken。
 */
@Slf4j
@Component
public class JwtUtils {

    /** HS256 安全密钥最低长度（字节）= 256 bit / 8 */
    private static final int MIN_SECRET_BYTES = 32;

    /** application.yml 中暴露的 dev 默认密钥（仅 dev profile 允许使用，prod 强制报错） */
    private static final String DEV_DEFAULT_SECRET =
            "dev-only-secret-please-change-in-production-0123456789";

    @Value("${finance.jwt.secret}")
    private String secret;

    @Value("${finance.jwt.expire}")
    private long expire;

    @Value("${finance.jwt.refresh-expire:604800}")
    private long refreshExpire;

    @Value("${finance.jwt.header}")
    private String header;

    @Value("${finance.jwt.prefix}")
    private String prefix;

    /** 注入 Environment 用于校验当前激活的 profile */
    private final Environment environment;

    public JwtUtils(Environment environment) {
        this.environment = environment;
    }

    public String getHeader() {
        return header;
    }

    public String getPrefix() {
        return prefix;
    }

    /**
     * 启动时校验 JWT 密钥：
     * <ul>
     *   <li>密钥长度必须 ≥ 32 字节（HS256 安全要求，对应 256 bit）</li>
     *   <li>非 dev profile 下禁止使用 application.yml 中暴露的默认密钥</li>
     * </ul>
     * 任一不通过则启动失败，避免生产环境用弱密钥签发 JWT 被伪造。
     */
    @PostConstruct
    public void validateSecret() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "[JWT] finance.jwt.secret 未配置；生产环境必须通过环境变量 JWT_SECRET 注入 ≥32 字节随机串");
        }
        int byteLen = secret.getBytes(StandardCharsets.UTF_8).length;
        if (byteLen < MIN_SECRET_BYTES) {
            throw new IllegalStateException(String.format(
                    "[JWT] finance.jwt.secret 长度不足：%d 字节（< %d 字节 / 256 bit），"
                            + "HS256 要求密钥至少 256 bit；请通过环境变量 JWT_SECRET 注入更长的随机串",
                    byteLen, MIN_SECRET_BYTES));
        }
        List<String> activeProfiles = Arrays.asList(environment.getActiveProfiles());
        boolean isDev = activeProfiles.isEmpty() || activeProfiles.contains("dev");
        if (DEV_DEFAULT_SECRET.equals(secret)) {
            if (isDev) {
                log.warn("[JWT] 当前使用 application.yml 中的 dev 默认密钥，仅限本地开发；"
                        + "生产环境请通过环境变量 JWT_SECRET 注入 ≥32 字节随机串");
            } else {
                throw new IllegalStateException(String.format(
                        "[JWT] 当前 profile=%s 禁止使用 dev 默认密钥；"
                                + "生产环境必须通过环境变量 JWT_SECRET 注入 ≥32 字节随机串",
                        activeProfiles));
            }
        }
    }

    private SecretKey key() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成 accessToken（type=access）
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
                .claim("type", "access")
                .issuedAt(now)
                .expiration(expiration)
                .signWith(key())
                .compact();
    }

    /**
     * 生成 refreshToken（type=refresh）
     * <p>
     * 不携带 roles，仅用于续期。续期时需重新查库获取最新角色，避免角色变更后旧 refreshToken 续出的 accessToken 仍带旧角色。
     *
     * @param userId 用户ID
     * @param username 用户名
     */
    public String generateRefreshToken(Long userId, String username) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + refreshExpire * 1000);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("type", "refresh")
                .issuedAt(now)
                .expiration(expiration)
                .signWith(key())
                .compact();
    }

    /**
     * 解析 token（不区分类型）
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 校验 token 是否有效（不区分类型）
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
     * 校验是否为有效的 accessToken
     * <p>
     * 业务接口鉴权时调用，拒绝 refreshToken 被误用。
     */
    public boolean isValidAccessToken(String token) {
        try {
            Claims claims = parse(token);
            return "access".equals(claims.get("type", String.class))
                    && claims.getExpiration().after(new Date());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 校验是否为有效的 refreshToken
     * <p>
     * 仅 /auth/refresh 接口调用，拒绝 accessToken 被误用为续期凭证。
     */
    public boolean isValidRefreshToken(String token) {
        try {
            Claims claims = parse(token);
            return "refresh".equals(claims.get("type", String.class))
                    && claims.getExpiration().after(new Date());
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
     * 从 token 提取用户名
     */
    public String getUsername(String token) {
        return parse(token).get("username", String.class);
    }

    /**
     * 从 accessToken 提取角色列表
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
