package com.finance.platform.system.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finance.platform.common.core.Result;
import com.finance.platform.system.entity.SysUser;
import com.finance.platform.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 系统用户管理接口
 * <p>
 * 提供用户分页查询、增删改、重置密码、分配角色等能力。
 * 所有接口仅管理员可用。
 */
@Slf4j
@RestController
@RequestMapping("/system/user")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class SysUserController {

    private final SysUserService sysUserService;

    /**
     * 分页查询用户
     *
     * @param keyword 关键词（同时匹配用户名和真实姓名，OR 关系）
     * @param status  状态：0 禁用 / 1 启用
     * @param roleCode 角色代码（如 ADMIN / FINANCE / APPROVER / OPERATOR）
     */
    @GetMapping("/page")
    public Result<Page<SysUser>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String roleCode) {
        Page<SysUser> p = new Page<>(page, size);
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .eq(status != null, SysUser::getStatus, status)
                // roleIds 以 JSON 字符串存储（如 ["ADMIN","FINANCE"]），用 LIKE 匹配角色代码
                .like(StrUtil.isNotBlank(roleCode), SysUser::getRoleIds, roleCode)
                .orderByDesc(SysUser::getId);
        // 关键词同时匹配用户名和真实姓名（OR 关系）
        if (StrUtil.isNotBlank(keyword)) {
            wrapper.and(w -> w.like(SysUser::getUsername, keyword)
                    .or().like(SysUser::getRealName, keyword));
        }
        sysUserService.page(p, wrapper);
        return Result.success(p);
    }

    /**
     * 新增用户（密码自动 BCrypt 加密）
     */
    @PostMapping
    public Result<Void> add(@RequestBody SysUser user) {
        sysUserService.createUser(user);
        return Result.success();
    }

    /**
     * 修改用户（password 留空则不修改密码）
     */
    @PutMapping
    public Result<Void> update(@RequestBody SysUser user) {
        sysUserService.updateUser(user);
        return Result.success();
    }

    /**
     * 删除用户
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        sysUserService.removeById(id);
        return Result.success();
    }

    /**
     * 重置密码
     */
    @PutMapping("/{id}/reset-password")
    public Result<Void> resetPassword(@PathVariable Long id, @RequestBody Map<String, String> body) {
        sysUserService.resetPassword(id, body.get("newPassword"));
        return Result.success();
    }

    /**
     * 分配角色
     *
     * @param roleCodes 角色代码列表（如 ADMIN / FINANCE / APPROVER / OPERATOR）
     */
    @PutMapping("/{id}/roles")
    public Result<Void> assignRoles(@PathVariable Long id, @RequestBody List<String> roleCodes) {
        sysUserService.assignRoles(id, roleCodes);
        return Result.success();
    }
}
