package com.finance.platform.fund.service.impl;

import com.finance.platform.common.exception.BusinessException;
import com.finance.platform.fund.entity.BudgetPlan;
import com.finance.platform.fund.mapper.BudgetPlanMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 预算控制服务单元测试
 * <p>
 * 验证：
 * 1. checkBudget：正常/超支/预算计划不存在
 * 2. updateUsedAmount：原子扣减成功/超支失败/计划不存在
 * 3. getWarningPlans：阈值预警判断
 */
@DisplayName("预算控制服务测试")
@ExtendWith(MockitoExtension.class)
class BudgetControlServiceImplTest {

    @Mock
    private BudgetPlanMapper budgetPlanMapper;

    @InjectMocks
    private BudgetControlServiceImpl budgetControlService;

    private BudgetPlan buildPlan(Long id, BigDecimal total, BigDecimal used, BigDecimal threshold) {
        BudgetPlan plan = new BudgetPlan();
        plan.setId(id);
        plan.setTotalAmount(total);
        plan.setUsedAmount(used);
        plan.setWarningThreshold(threshold);
        plan.setPlanName("测试预算");
        plan.setPeriod("202607");
        return plan;
    }

    // ==================== checkBudget ====================
    @Test
    @DisplayName("checkBudget 未超支返回 true")
    void checkBudgetNotExceeded() {
        BudgetPlan plan = buildPlan(1L, new BigDecimal("10000"), new BigDecimal("3000"), new BigDecimal("80"));
        when(budgetPlanMapper.selectById(1L)).thenReturn(plan);

        boolean result = budgetControlService.checkBudget(1L, new BigDecimal("5000"));

        assertThat(result).isTrue(); // 3000 + 5000 = 8000 <= 10000
    }

    @Test
    @DisplayName("checkBudget 刚好等于总额返回 true")
    void checkBudgetExactlyAtTotal() {
        BudgetPlan plan = buildPlan(1L, new BigDecimal("10000"), new BigDecimal("5000"), null);
        when(budgetPlanMapper.selectById(1L)).thenReturn(plan);

        boolean result = budgetControlService.checkBudget(1L, new BigDecimal("5000"));

        assertThat(result).isTrue(); // 5000 + 5000 = 10000 <= 10000
    }

    @Test
    @DisplayName("checkBudget 超支返回 false")
    void checkBudgetExceeded() {
        BudgetPlan plan = buildPlan(1L, new BigDecimal("10000"), new BigDecimal("8000"), null);
        when(budgetPlanMapper.selectById(1L)).thenReturn(plan);

        boolean result = budgetControlService.checkBudget(1L, new BigDecimal("3000"));

        assertThat(result).isFalse(); // 8000 + 3000 = 11000 > 10000
    }

    @Test
    @DisplayName("checkBudget 预算计划不存在抛异常")
    void checkBudgetPlanNotFound() {
        when(budgetPlanMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> budgetControlService.checkBudget(999L, new BigDecimal("100")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("预算计划不存在");
    }

    @Test
    @DisplayName("checkBudget usedAmount 为 null 时按 0 处理")
    void checkBudgetNullUsedAmount() {
        BudgetPlan plan = buildPlan(1L, new BigDecimal("10000"), null, null);
        when(budgetPlanMapper.selectById(1L)).thenReturn(plan);

        boolean result = budgetControlService.checkBudget(1L, new BigDecimal("10000"));

        assertThat(result).isTrue(); // 0 + 10000 = 10000 <= 10000
    }

    // ==================== updateUsedAmount ====================
    @Test
    @DisplayName("updateUsedAmount 原子扣减成功")
    void updateUsedAmountSuccess() {
        BudgetPlan plan = buildPlan(1L, new BigDecimal("10000"), new BigDecimal("3000"), null);
        when(budgetPlanMapper.selectById(1L)).thenReturn(plan);
        when(budgetPlanMapper.atomicDeduct(1L, new BigDecimal("2000"))).thenReturn(1);

        budgetControlService.updateUsedAmount(1L, new BigDecimal("2000"));

        verify(budgetPlanMapper).atomicDeduct(1L, new BigDecimal("2000"));
    }

    @Test
    @DisplayName("updateUsedAmount 原子扣减返回 0 时抛超支异常")
    void updateUsedAmountExceededThrows() {
        BudgetPlan plan = buildPlan(1L, new BigDecimal("10000"), new BigDecimal("9000"), null);
        when(budgetPlanMapper.selectById(1L)).thenReturn(plan);
        when(budgetPlanMapper.atomicDeduct(1L, new BigDecimal("2000"))).thenReturn(0);

        assertThatThrownBy(() -> budgetControlService.updateUsedAmount(1L, new BigDecimal("2000")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("预算超支，扣减失败");
    }

    @Test
    @DisplayName("updateUsedAmount 预算计划不存在抛异常")
    void updateUsedAmountPlanNotFound() {
        when(budgetPlanMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> budgetControlService.updateUsedAmount(999L, new BigDecimal("100")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("预算计划不存在");
        verify(budgetPlanMapper, never()).atomicDeduct(anyLong(), any());
    }

    @Test
    @DisplayName("updateUsedAmount delta 为 null 时按 0 处理")
    void updateUsedAmountNullDelta() {
        BudgetPlan plan = buildPlan(1L, new BigDecimal("10000"), new BigDecimal("3000"), null);
        when(budgetPlanMapper.selectById(1L)).thenReturn(plan);
        when(budgetPlanMapper.atomicDeduct(1L, BigDecimal.ZERO)).thenReturn(1);

        budgetControlService.updateUsedAmount(1L, null);

        verify(budgetPlanMapper).atomicDeduct(1L, BigDecimal.ZERO);
    }

    // ==================== getWarningPlans ====================
    @Test
    @DisplayName("getWarningPlans 返回达到预警阈值的计划")
    void getWarningPlansReturnsExceededThreshold() {
        BudgetPlan normal = buildPlan(1L, new BigDecimal("10000"), new BigDecimal("3000"), new BigDecimal("80"));
        BudgetPlan warning = buildPlan(2L, new BigDecimal("10000"), new BigDecimal("8500"), new BigDecimal("80"));
        when(budgetPlanMapper.selectList(null)).thenReturn(Arrays.asList(normal, warning));

        List<BudgetPlan> result = budgetControlService.getWarningPlans();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(2L); // 8500/10000 = 85% >= 80%
    }

    @Test
    @DisplayName("getWarningPlans 刚好等于阈值也被预警")
    void getWarningPlansExactlyAtThreshold() {
        BudgetPlan plan = buildPlan(1L, new BigDecimal("10000"), new BigDecimal("8000"), new BigDecimal("80"));
        when(budgetPlanMapper.selectList(null)).thenReturn(Collections.singletonList(plan));

        List<BudgetPlan> result = budgetControlService.getWarningPlans();

        assertThat(result).hasSize(1); // 80% >= 80%
    }

    @Test
    @DisplayName("getWarningPlans 阈值为 0 或总额为 0 时跳过")
    void getWarningPlansSkipsZeroThresholdOrTotal() {
        BudgetPlan zeroThreshold = buildPlan(1L, new BigDecimal("10000"), new BigDecimal("9000"), BigDecimal.ZERO);
        BudgetPlan zeroTotal = buildPlan(2L, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("80"));
        when(budgetPlanMapper.selectList(null)).thenReturn(Arrays.asList(zeroThreshold, zeroTotal));

        List<BudgetPlan> result = budgetControlService.getWarningPlans();

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getWarningPlans 无计划时返回空列表")
    void getWarningPlansEmpty() {
        when(budgetPlanMapper.selectList(null)).thenReturn(Collections.emptyList());

        List<BudgetPlan> result = budgetControlService.getWarningPlans();

        assertThat(result).isEmpty();
    }
}
