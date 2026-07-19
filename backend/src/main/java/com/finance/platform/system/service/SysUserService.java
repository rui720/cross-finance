package com.finance.platform.system.service;

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
     * @return JWT token
     */
    String login(String username, String password);

    /**
     * 新增用户：BCrypt 加密密码后入库
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
     * @param roleCodes 角色代码列表（如 ADMIN / FINANCE / APPROVER / OPERATOR）
     */
    void assignRoles(Long userId, List<String> roleCodes);
}
