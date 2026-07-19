package com.finance.platform.fund.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.finance.platform.fund.entity.BudgetPlan;

import java.math.BigDecimal;
import java.util.List;

/**
 * 预算校验服务接口
 * <p>
 * 提供预算超支校验、已使用金额增减、超阈值预算预警查询能力。
 */
public interface BudgetControlService extends IService<BudgetPlan> {

    /**
     * 校验预算是否足够
     *
     * @param budgetPlanId 预算计划 ID
     * @param amount       本次拟使用金额
     * @return true 表示未超支（校验通过），false 表示超支
     */
    boolean checkBudget(Long budgetPlanId, BigDecimal amount);

    /**
     * 增减已使用金额（delta 正数表示占用，负数表示释放）
     *
     * @param budgetPlanId 预算计划 ID
     * @param delta        变化量
     */
    void updateUsedAmount(Long budgetPlanId, BigDecimal delta);

    /**
     * 查询已达到预警阈值的预算计划
     *
     * @return 超阈值预算计划列表
     */
    List<BudgetPlan> getWarningPlans();
}
