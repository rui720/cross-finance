package com.finance.platform.common.core;

/**
 * 登录用户信息（作为 SecurityContext 的 principal）
 * <p>
 * 同时持有 userId 与 username，供 MyMetaObjectHandler 自动填充 createBy/updateBy
 * 以及 AuditLogAspect 填充审计日志的 userId/username 使用。
 *
 * @param userId   用户 ID
 * @param username 用户名
 */
public record LoginUser(Long userId, String username) {
}
