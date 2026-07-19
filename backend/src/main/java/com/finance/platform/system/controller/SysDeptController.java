package com.finance.platform.system.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.platform.common.core.Result;
import com.finance.platform.system.entity.SysDept;
import com.finance.platform.system.mapper.SysDeptMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 部门接口
 * <p>
 * 提供部门列表查询（树形/平铺）、新增、修改、删除。
 * 部门列表对所有登录用户开放（用户表单需要下拉选择），写操作仅管理员。
 */
@Slf4j
@RestController
@RequestMapping("/system/dept")
@RequiredArgsConstructor
public class SysDeptController {

    private final SysDeptMapper sysDeptMapper;

    /**
     * 查询全部启用部门（平铺列表，前端按 parent_id 自行构建树）
     * 所有登录用户均可访问（用户表单需要部门下拉）
     */
    @GetMapping("/list")
    public Result<List<SysDept>> list(@RequestParam(required = false) Integer status) {
        List<SysDept> list = sysDeptMapper.selectList(new LambdaQueryWrapper<SysDept>()
                .eq(status != null, SysDept::getStatus, status)
                .orderByAsc(SysDept::getSort)
                .orderByAsc(SysDept::getId));
        return Result.success(list);
    }

    /**
     * 新增部门（仅管理员）
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> add(@RequestBody SysDept dept) {
        sysDeptMapper.insert(dept);
        return Result.success();
    }

    /**
     * 修改部门（仅管理员）
     */
    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> update(@RequestBody SysDept dept) {
        sysDeptMapper.updateById(dept);
        return Result.success();
    }

    /**
     * 删除部门（仅管理员）
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> delete(@PathVariable Long id) {
        sysDeptMapper.deleteById(id);
        return Result.success();
    }
}
