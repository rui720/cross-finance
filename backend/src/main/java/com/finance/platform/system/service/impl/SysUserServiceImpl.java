package com.finance.platform.system.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.finance.platform.common.exception.BusinessException;
import com.finance.platform.common.utils.JwtUtils;
import com.finance.platform.system.entity.SysUser;
import com.finance.platform.system.mapper.SysUserMapper;
import com.finance.platform.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 系统用户服务实现
 * <p>
 * 登录校验、密码重置、角色分配等核心业务逻辑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements SysUserService {

    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;

    @Override
    public String login(String username, String password) {
        SysUser user = getOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username));
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        if (user.getStatus() == null || user.getStatus() == 0) {
            throw new BusinessException("账号已被禁用");
        }
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new BusinessException("用户名或密码错误");
        }
        // roleIds 以 JSON 数组字符串存储，解析为角色列表后写入 JWT
        // 角色为空时默认赋予 EMPLOYEE（普通员工），保证空角色用户也有基础访问权限
        List<String> roles = StrUtil.isBlank(user.getRoleIds())
                ? List.of("EMPLOYEE")
                : JSONUtil.parseArray(user.getRoleIds()).toList(String.class);
        log.info("[Login] 用户登录成功 userId={}, username={}", user.getId(), username);
        return jwtUtils.generate(user.getId(), user.getUsername(), roles);
    }

    @Override
    public void createUser(SysUser user) {
        if (StrUtil.isBlank(user.getUsername())) {
            throw new BusinessException("用户名不能为空");
        }
        long exists = count(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, user.getUsername()));
        if (exists > 0) {
            throw new BusinessException("用户名已存在");
        }
        if (StrUtil.isBlank(user.getPassword())) {
            throw new BusinessException("密码不能为空");
        }
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        if (user.getStatus() == null) {
            user.setStatus(1);
        }
        save(user);
        log.info("[User] 新增用户 userId={}, username={}", user.getId(), user.getUsername());
    }

    @Override
    public void updateUser(SysUser user) {
        if (user.getId() == null) {
            throw new BusinessException("用户 ID 不能为空");
        }
        // password 非空才加密更新，避免覆盖为空
        if (StrUtil.isNotBlank(user.getPassword())) {
            user.setPassword(passwordEncoder.encode(user.getPassword()));
        } else {
            user.setPassword(null);
        }
        updateById(user);
        log.info("[User] 修改用户 userId={}", user.getId());
    }

    @Override
    public void resetPassword(Long userId, String newPassword) {
        SysUser user = getById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        SysUser update = new SysUser();
        update.setId(userId);
        update.setPassword(passwordEncoder.encode(newPassword));
        updateById(update);
        log.info("[Password] 用户密码已重置 userId={}", userId);
    }

    @Override
    public void assignRoles(Long userId, List<String> roleCodes) {
        SysUser user = getById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        SysUser update = new SysUser();
        update.setId(userId);
        update.setRoleIds(JSONUtil.toJsonStr(roleCodes));
        updateById(update);
        log.info("[Role] 用户角色已分配 userId={}, roleCodes={}", userId, roleCodes);
    }
}
