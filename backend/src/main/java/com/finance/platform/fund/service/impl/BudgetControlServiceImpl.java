package com.finance.platform.fund.service.impl;

import com.finance.platform.common.exception.BusinessException;
import com.finance.platform.fund.entity.BudgetPlan;
import com.finance.platform.fund.mapper.BudgetPlanMapper;
import com.finance.platform.fund.service.BudgetControlService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 预算超支校验实现
 * <p>
 * checkBudget 在 usedAmount + amount 超过 totalAmount 时返回 false；
 * getWarningPlans 查询已使用占比达到预警阈值的预算计划。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BudgetControlServiceImpl extends ServiceImpl<BudgetPlanMapper, BudgetPlan> implements BudgetControlService {

    private final BudgetPlanMapper budgetPlanMapper;

    @Override
    public boolean checkBudget(Long budgetPlanId, BigDecimal amount) {
        BudgetPlan plan = budgetPlanMapper.selectById(budgetPlanId);
        if (plan == null) {
            throw new BusinessException("预算计划不存在: " + budgetPlanId);
        }
        BigDecimal used = plan.getUsedAmount() == null ? BigDecimal.ZERO : plan.getUsedAmount();
        BigDecimal total = plan.getTotalAmount() == null ? BigDecimal.ZERO : plan.getTotalAmount();
        BigDecimal add = amount == null ? BigDecimal.ZERO : amount;
        // usedAmount + amount > totalAmount 时返回 false（超支）
        return used.add(add).compareTo(total) <= 0;
    }

    @Override
    public void updateUsedAmount(Long budgetPlanId, BigDecimal delta) {
        BudgetPlan plan = budgetPlanMapper.selectById(budgetPlanId);
        if (plan == null) {
            throw new BusinessException("预算计划不存在: " + budgetPlanId);
        }
        BigDecimal d = delta == null ? BigDecimal.ZERO : delta;
        // 原子扣减：UPDATE ... WHERE used + delta <= total，避免并发超支
        int rows = budgetPlanMapper.atomicDeduct(budgetPlanId, d);
        if (rows == 0) {
            throw new BusinessException("预算超支，扣减失败: planId=" + budgetPlanId + " delta=" + d);
        }
        log.info("[预算] 原子扣减成功 planId={} delta={}", budgetPlanId, d);
    }

    @Override
    public List<BudgetPlan> getWarningPlans() {
        List<BudgetPlan> all = budgetPlanMapper.selectList(null);
        List<BudgetPlan> warning = new ArrayList<>();
        for (BudgetPlan plan : all) {
            BigDecimal used = plan.getUsedAmount() == null ? BigDecimal.ZERO : plan.getUsedAmount();
            BigDecimal total = plan.getTotalAmount() == null ? BigDecimal.ZERO : plan.getTotalAmount();
            BigDecimal threshold = plan.getWarningThreshold() == null ? BigDecimal.ZERO : plan.getWarningThreshold();
            if (total.compareTo(BigDecimal.ZERO) <= 0 || threshold.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            // usedAmount / totalAmount >= warningThreshold / 100
            BigDecimal ratio = used.divide(total, 6, RoundingMode.HALF_UP);
            BigDecimal thresholdRatio = threshold.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
            if (ratio.compareTo(thresholdRatio) >= 0) {
                warning.add(plan);
            }
        }
        return warning;
    }
}
