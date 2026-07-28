package com.finance.platform.system.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.finance.platform.system.entity.SysUser;

import java.util.List;

/**
 * 系统用户服务接口
 */
public interface SysUserService extends IService<SysUser> {

    /**
     * 用户登录：校验账号密码与状态，生成 JWT 返回
     *
     * @param username 用户名
     * @param password 明文密码
     * @return JWT accessToken（向后兼容）
     */
    String login(String username, String password);

    /**
     * 用户登录（双 Token 版本）：校验账号密码与状态，同时签发 accessToken 与 refreshToken
     *
     * @param username 用户名
     * @param password 明文密码
     * @return 包含 accessToken、refreshToken、user 的登录结果
     */
    LoginResult loginWithRefresh(String username, String password);

    /**
     * 使用 refreshToken 续签 accessToken（同时轮换 refreshToken）
     * <p>
     * 重新查库获取最新角色，避免角色变更后旧 refreshToken 续出的 accessToken 仍带旧角色。
     *
     * @param refreshToken 有效的 refreshToken
     * @return 新的 accessToken + refreshToken
     */
    LoginResult refresh(String refreshToken);

    /**
     * 登录结果封装
     *
     * @param accessToken  短期访问令牌（2 小时）
     * @param refreshToken 长期刷新令牌（7 天）
     * @param user         用户信息（password 已 @JsonIgnore）
     */
    record LoginResult(String accessToken, String refreshToken, SysUser user) {}

    /**
     * 新增用户：BCrypt 加密密码后入库
     * <p>
     * 校验 employee_no/phone/email 在 deleted=0 范围内的唯一性。
     * 用户名 username 不再要求全局唯一，逻辑删除的 username 可被复用。
     * 若未指定 employeeNo，将自动生成（EMP + 年份 + 4 位顺序号）。
     *
     * @param user 用户信息（password 为明文）
     */
    void createUser(SysUser user);

    /**
     * 修改用户：若 password 非空则 BCrypt 加密后更新
     *
     * @param user 用户信息
     */
    void updateUser(SysUser user);

    /**
     * 重置用户密码（BCrypt 加密后更新）
     *
     * @param userId      用户 ID
     * @param newPassword 新密码（明文）
     */
    void resetPassword(Long userId, String newPassword);

    /**
     * 给用户分配角色
     *
     * @param userId    用户 ID
     * @param roleCodes 角色代码列表（如 ADMIN / FINANCE / OPERATOR / EMPLOYEE）
     */
    void assignRoles(Long userId, List<String> roleCodes);

    /**
     * 分页查询已逻辑删除的用户（用于恢复入口）。
     *
     * @param page    页码（从 1 起）
     * @param size    每页大小
     * @param keyword 关键词（同时匹配用户名、真实姓名、工号，可为空）
     * @return 已删除用户分页结果
     */
    Page<SysUser> pageDeleted(long page, long size, String keyword);

    /**
     * 恢复已逻辑删除的用户。
     * <p>
     * 恢复前校验 employee_no/phone/email 在 deleted=0 范围内是否冲突：
     * 若冲突，抛出业务异常并提示具体冲突字段，管理员需先处理冲突再恢复。
     *
     * @param userId 待恢复用户 ID
     */
    void recoverUser(Long userId);
}
