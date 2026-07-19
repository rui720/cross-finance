package com.finance.platform.fund.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.platform.common.core.Result;
import com.finance.platform.fund.entity.BudgetPlan;
import com.finance.platform.fund.mapper.BudgetPlanMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 预算计划查询接口
 * <p>
 * 提供预算计划列表查询，供付款申请表单下拉选择使用。
 * 权限：ADMIN / FINANCE / OPERATOR 可查询（发起付款申请时需要选择预算）。
 */
@Slf4j
@RestController
@RequestMapping("/fund/budget")
@RequiredArgsConstructor
public class BudgetPlanController {

    private final BudgetPlanMapper budgetPlanMapper;

    /**
     * 查询启用中的预算计划列表
     *
     * @param period 可选周期过滤（如 202607）
     */
    @GetMapping("/list")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','OPERATOR')")
    public Result<List<BudgetPlan>> list(@RequestParam(required = false) String period) {
        List<BudgetPlan> list = budgetPlanMapper.selectList(new LambdaQueryWrapper<BudgetPlan>()
                .eq(period != null && !period.isBlank(), BudgetPlan::getPeriod, period)
                .orderByDesc(BudgetPlan::getId));
        return Result.success(list);
    }
}
