package com.finance.platform.system.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.finance.platform.common.exception.BusinessException;
import com.finance.platform.common.utils.JwtUtils;
import com.finance.platform.system.entity.SysUser;
import com.finance.platform.system.mapper.SysUserMapper;
import com.finance.platform.system.mapper.UndoMapper;
import com.finance.platform.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 系统用户服务实现
 * <p>
 * 登录校验、密码重置、角色分配、用户恢复等核心业务逻辑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements SysUserService {

    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final SysUserMapper sysUserMapper;
    private final UndoMapper undoMapper;

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
    public SysUserService.LoginResult loginWithRefresh(String username, String password) {
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
        List<String> roles = resolveRoles(user);
        String accessToken = jwtUtils.generate(user.getId(), user.getUsername(), roles);
        String refreshToken = jwtUtils.generateRefreshToken(user.getId(), user.getUsername());
        log.info("[Login] 用户登录成功（双 Token）userId={}, username={}", user.getId(), username);
        return new SysUserService.LoginResult(accessToken, refreshToken, user);
    }

    @Override
    public SysUserService.LoginResult refresh(String refreshToken) {
        if (!jwtUtils.isValidRefreshToken(refreshToken)) {
            throw new BusinessException("refreshToken 无效或已过期，请重新登录");
        }
        Long userId = jwtUtils.getUserId(refreshToken);
        SysUser user = getById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        if (user.getStatus() == null || user.getStatus() == 0) {
            throw new BusinessException("账号已被禁用");
        }
        // 重新查库获取最新角色，避免角色变更后旧 refreshToken 续出的 accessToken 仍带旧角色
        List<String> roles = resolveRoles(user);
        String newAccessToken = jwtUtils.generate(user.getId(), user.getUsername(), roles);
        String newRefreshToken = jwtUtils.generateRefreshToken(user.getId(), user.getUsername());
        log.info("[Refresh] 续签成功 userId={}, username={}", user.getId(), user.getUsername());
        return new SysUserService.LoginResult(newAccessToken, newRefreshToken, user);
    }

    /** 解析用户角色：空则默认 EMPLOYEE */
    private List<String> resolveRoles(SysUser user) {
        return StrUtil.isBlank(user.getRoleIds())
                ? List.of("EMPLOYEE")
                : JSONUtil.parseArray(user.getRoleIds()).toList(String.class);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createUser(SysUser user) {
        if (StrUtil.isBlank(user.getUsername())) {
            throw new BusinessException("用户名不能为空");
        }
        if (StrUtil.isBlank(user.getPassword())) {
            throw new BusinessException("密码不能为空");
        }
        // 工号自动生成（前端未传时）：EMP + 年份 + 4 位顺序号
        if (StrUtil.isBlank(user.getEmployeeNo())) {
            user.setEmployeeNo(generateEmployeeNo());
        }
        // 工号唯一性校验（仅校验未删除记录，工号是业务唯一标识）
        long empNoExists = count(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getEmployeeNo, user.getEmployeeNo()));
        if (empNoExists > 0) {
            throw new BusinessException("员工工号「" + user.getEmployeeNo() + "」已存在，请检查或更换工号");
        }
        // 用户名不再要求唯一（删除后可复用），仅校验非空
        // 手机号唯一性校验（如填写了手机号）
        if (StrUtil.isNotBlank(user.getPhone())) {
            long phoneExists = count(new LambdaQueryWrapper<SysUser>().eq(SysUser::getPhone, user.getPhone()));
            if (phoneExists > 0) {
                throw new BusinessException("手机号「" + user.getPhone() + "」已被其他用户使用");
            }
        }
        // 邮箱唯一性校验（如填写了邮箱）
        if (StrUtil.isNotBlank(user.getEmail())) {
            long emailExists = count(new LambdaQueryWrapper<SysUser>().eq(SysUser::getEmail, user.getEmail()));
            if (emailExists > 0) {
                throw new BusinessException("邮箱「" + user.getEmail() + "」已被其他用户使用");
            }
        }
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        if (user.getStatus() == null) {
            user.setStatus(1);
        }
        try {
            save(user);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 数据库唯一约束兜底（并发情况下应用层校验通过但插入冲突）
            log.warn("[User] 新增用户唯一键冲突 employeeNo={}, username={}", user.getEmployeeNo(), user.getUsername());
            throw new BusinessException("新增失败：关键字段（工号/手机号/邮箱）在数据库层冲突，请检查后重试");
        }
        log.info("[User] 新增用户 userId={}, employeeNo={}, username={}", user.getId(), user.getEmployeeNo(), user.getUsername());
    }

    /**
     * 自动生成员工工号：EMP + 4 位年份 + 4 位顺序号（基于当年已有工号最大序号 +1）。
     * <p>
     * 示例：EMP20260001、EMP20260002 ... EMP20269999
     * 并发场景下序号可能冲突，由调用方 save() 时数据库索引兜底（如有需要可加唯一索引）。
     */
    private String generateEmployeeNo() {
        int year = LocalDate.now().getYear();
        String prefix = "EMP" + year;
        // 查询当前已存在的最大工号序号（包含已删除的，避免序号复用造成业务混淆）
        SysUser maxUser = getBaseMapper().selectOne(new LambdaQueryWrapper<SysUser>()
                .likeRight(SysUser::getEmployeeNo, prefix)
                .orderByDesc(SysUser::getEmployeeNo)
                .last("LIMIT 1"));
        int nextSeq = 1;
        if (maxUser != null && StrUtil.isNotBlank(maxUser.getEmployeeNo())) {
            try {
                String seqStr = maxUser.getEmployeeNo().substring(prefix.length());
                nextSeq = Integer.parseInt(seqStr) + 1;
            } catch (NumberFormatException ignored) {
                // 解析失败则从 1 开始
            }
        }
        return prefix + String.format("%04d", nextSeq);
    }

    @Override
    public void updateUser(SysUser user) {
        if (user.getId() == null) {
            throw new BusinessException("用户 ID 不能为空");
        }
        SysUser existing = getById(user.getId());
        if (existing == null) {
            throw new BusinessException("用户不存在");
        }
        // 工号允许修改，但需校验新工号在 deleted=0 范围内的唯一性
        if (StrUtil.isNotBlank(user.getEmployeeNo()) && !user.getEmployeeNo().equals(existing.getEmployeeNo())) {
            long empNoExists = count(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getEmployeeNo, user.getEmployeeNo())
                    .ne(SysUser::getId, user.getId()));
            if (empNoExists > 0) {
                throw new BusinessException("员工工号「" + user.getEmployeeNo() + "」已被其他用户使用");
            }
        } else if (user.getEmployeeNo() == null) {
            // 未传工号时不更新该字段
            user.setEmployeeNo(null);
        }
        // 用户名允许修改（用户名不唯一，但同名会导致登录歧义，前端会提示）
        // 手机号唯一性校验（如修改了手机号）
        if (StrUtil.isNotBlank(user.getPhone()) && !user.getPhone().equals(existing.getPhone())) {
            long phoneExists = count(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getPhone, user.getPhone())
                    .ne(SysUser::getId, user.getId()));
            if (phoneExists > 0) {
                throw new BusinessException("手机号「" + user.getPhone() + "」已被其他用户使用");
            }
        }
        // 邮箱唯一性校验（如修改了邮箱）
        if (StrUtil.isNotBlank(user.getEmail()) && !user.getEmail().equals(existing.getEmail())) {
            long emailExists = count(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getEmail, user.getEmail())
                    .ne(SysUser::getId, user.getId()));
            if (emailExists > 0) {
                throw new BusinessException("邮箱「" + user.getEmail() + "」已被其他用户使用");
            }
        }
        // password 非空才加密更新，避免覆盖为空
        if (StrUtil.isNotBlank(user.getPassword())) {
            user.setPassword(passwordEncoder.encode(user.getPassword()));
        } else {
            user.setPassword(null);
        }
        try {
            updateById(user);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new BusinessException("修改失败：关键字段在数据库层冲突，请检查工号/手机号/邮箱是否重复");
        }
        log.info("[User] 修改用户 userId={}, employeeNo={}, username={}", user.getId(), user.getEmployeeNo(), user.getUsername());
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

    @Override
    public Page<SysUser> pageDeleted(long page, long size, String keyword) {
        // 使用原生 SQL 查 deleted=1 的用户（绕过 MyBatis-Plus 逻辑删除过滤）
        List<SysUser> all = sysUserMapper.selectDeleted(keyword);
        Page<SysUser> p = new Page<>(page, size);
        p.setTotal(all.size());
        int from = (int) Math.min((page - 1) * size, all.size());
        int to = (int) Math.min(from + size, all.size());
        p.setRecords(all.subList(from, to));
        return p;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recoverUser(Long userId) {
        // 1. 校验目标用户存在且确为已删除状态
        SysUser deletedUser = sysUserMapper.selectDeletedById(userId);
        if (deletedUser == null) {
            throw new BusinessException("待恢复的用户不存在或未被删除");
        }
        // 2. 恢复前校验唯一键在 deleted=0 范围内的冲突
        // 2.1 工号冲突
        long empNoConflict = count(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getEmployeeNo, deletedUser.getEmployeeNo()));
        if (empNoConflict > 0) {
            throw new BusinessException("恢复失败：工号「" + deletedUser.getEmployeeNo()
                    + "」已被另一在职用户占用。请先修改占用方的工号，或将待恢复用户的工号改为新值后再恢复。");
        }
        // 2.2 手机号冲突
        if (StrUtil.isNotBlank(deletedUser.getPhone())) {
            long phoneConflict = count(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getPhone, deletedUser.getPhone()));
            if (phoneConflict > 0) {
                throw new BusinessException("恢复失败：手机号「" + deletedUser.getPhone()
                        + "」已被另一在职用户占用。请先修改占用方的手机号，或清空待恢复用户的手机号后再恢复。");
            }
        }
        // 2.3 邮箱冲突
        if (StrUtil.isNotBlank(deletedUser.getEmail())) {
            long emailConflict = count(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getEmail, deletedUser.getEmail()));
            if (emailConflict > 0) {
                throw new BusinessException("恢复失败：邮箱「" + deletedUser.getEmail()
                        + "」已被另一在职用户占用。请先修改占用方的邮箱，或清空待恢复用户的邮箱后再恢复。");
            }
        }
        // 3. 用户名冲突提示（不阻止恢复，因为 username 不再是业务唯一键；
        //    但登录时 getOne 可能匹配到多条记录会抛异常，故需提示管理员处理）
        long usernameConflict = count(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, deletedUser.getUsername()));
        if (usernameConflict > 0) {
            log.warn("[User] 恢复用户时检测到用户名冲突 userId={}, username={}", userId, deletedUser.getUsername());
            throw new BusinessException("恢复失败：用户名「" + deletedUser.getUsername()
                    + "」已被另一在职用户使用。请先修改其中一方的用户名（用户名可重复，但同名会导致登录歧义）。");
        }
        // 4. 执行恢复：直接 UPDATE deleted=0（绕过逻辑删除过滤）
        undoMapper.restoreUser(userId);
        log.info("[User] 恢复已删除用户 userId={}, employeeNo={}, username={}",
                userId, deletedUser.getEmployeeNo(), deletedUser.getUsername());
    }
}
