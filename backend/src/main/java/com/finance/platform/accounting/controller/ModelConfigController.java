package com.finance.platform.accounting.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finance.platform.accounting.entity.CostAllocationRule;
import com.finance.platform.accounting.mapper.CostAllocationRuleMapper;
import com.finance.platform.common.core.Result;
import com.finance.platform.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分摊规则配置接口
 * <p>
 * 提供费用分摊规则的 CRUD 及启用/禁用能力，供核算引擎选用规则。
 * 权限：查询对 ADMIN/FINANCE/OPERATOR 开放（运营只读）；写操作仅 ADMIN/FINANCE。
 */
@Slf4j
@RestController
@RequestMapping("/accounting/model")
@RequiredArgsConstructor
public class ModelConfigController {

    private final CostAllocationRuleMapper costAllocationRuleMapper;

    /**
     * 新增分摊规则
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public Result<CostAllocationRule> create(@RequestBody CostAllocationRule rule) {
        if (StrUtil.isBlank(rule.getRuleType())) {
            throw new BusinessException("规则类型不能为空");
        }
        if (rule.getEnabled() == null) {
            rule.setEnabled(1);
        }
        costAllocationRuleMapper.insert(rule);
        return Result.success(rule);
    }

    /**
     * 更新分摊规则
     */
    @PutMapping
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public Result<Void> update(@RequestBody CostAllocationRule rule) {
        if (rule.getId() == null) {
            throw new BusinessException("规则 ID 不能为空");
        }
        costAllocationRuleMapper.updateById(rule);
        return Result.success();
    }

    /**
     * 删除分摊规则
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public Result<Void> delete(@PathVariable Long id) {
        costAllocationRuleMapper.deleteById(id);
        return Result.success();
    }

    /**
     * 查询分摊规则详情
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','OPERATOR')")
    public Result<CostAllocationRule> get(@PathVariable Long id) {
        return Result.success(costAllocationRuleMapper.selectById(id));
    }

    /**
     * 分页查询分摊规则
     *
     * @param enabled 可选：0 禁用 / 1 启用
     */
    @GetMapping("/page")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','OPERATOR')")
    public Result<Page<CostAllocationRule>> page(@RequestParam(defaultValue = "1") long page,
                                                  @RequestParam(defaultValue = "10") long size,
                                                  @RequestParam(required = false) Integer enabled) {
        Page<CostAllocationRule> p = new Page<>(page, size);
        costAllocationRuleMapper.selectPage(p, new LambdaQueryWrapper<CostAllocationRule>()
                .eq(enabled != null, CostAllocationRule::getEnabled, enabled)
                .orderByDesc(CostAllocationRule::getId));
        return Result.success(p);
    }

    /**
     * 统一启用/禁用接口（对齐前端 PUT /{id}/enabled?enabled=0|1）
     */
    @PutMapping("/{id}/enabled")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public Result<Void> toggleEnabled(@PathVariable Long id, @RequestParam Integer enabled) {
        return toggle(id, enabled);
    }

    /**
     * 启用规则
     */
    @PutMapping("/{id}/enable")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public Result<Void> enable(@PathVariable Long id) {
        return toggle(id, 1);
    }

    /**
     * 禁用规则
     */
    @PutMapping("/{id}/disable")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public Result<Void> disable(@PathVariable Long id) {
        return toggle(id, 0);
    }

    private Result<Void> toggle(Long id, int enabled) {
        CostAllocationRule update = new CostAllocationRule();
        update.setId(id);
        update.setEnabled(enabled);
        costAllocationRuleMapper.updateById(update);
        log.info("[分摊规则] id={} enabled={}", id, enabled);
        return Result.success();
    }
}
