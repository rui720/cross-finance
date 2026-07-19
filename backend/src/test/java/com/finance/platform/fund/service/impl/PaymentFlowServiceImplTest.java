package com.finance.platform.fund.service.impl;

import com.finance.platform.common.constant.BusinessConstants;
import com.finance.platform.common.exception.BusinessException;
import com.finance.platform.fund.entity.PaymentApply;
import com.finance.platform.fund.mapper.PaymentApplyMapper;
import com.finance.platform.fund.service.BudgetControlService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 付款流程状态机单元测试
 * <p>
 * 验证：
 * 1. submit：金额<=0 抛异常，正常提交设置 PENDING 状态
 * 2. approve：非 PENDING 状态抛异常，预算校验+扣减联动，正常流转到 APPROVED
 * 3. reject：非 PENDING 状态抛异常，正常流转到 REJECTED
 * 4. markPaid：非 APPROVED 状态抛异常，正常流转到 PAID
 * 5. 申请不存在时抛异常
 */
@DisplayName("付款流程状态机测试")
@ExtendWith(MockitoExtension.class)
class PaymentFlowServiceImplTest {

    @Mock
    private PaymentApplyMapper paymentApplyMapper;

    @Mock
    private BudgetControlService budgetControlService;

    @InjectMocks
    private PaymentFlowServiceImpl paymentFlowService;

    private PaymentApply buildApply(Long id, int status, BigDecimal amount, Long budgetPlanId) {
        PaymentApply apply = new PaymentApply();
        apply.setId(id);
        apply.setStatus(status);
        apply.setAmount(amount);
        apply.setBudgetPlanId(budgetPlanId);
        apply.setApplyNo("PAY20260715001");
        return apply;
    }

    // ==================== submit ====================
    @Test
    @DisplayName("submit 金额为 0 抛异常")
    void submitZeroAmountThrows() {
        PaymentApply apply = buildApply(null, 0, BigDecimal.ZERO, null);
        assertThatThrownBy(() -> paymentFlowService.submit(apply))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("付款金额必须大于 0");
        verify(paymentApplyMapper, never()).insert(any(PaymentApply.class));
    }

    @Test
    @DisplayName("submit 金额为负数抛异常")
    void submitNegativeAmountThrows() {
        PaymentApply apply = buildApply(null, 0, new BigDecimal("-100"), null);
        assertThatThrownBy(() -> paymentFlowService.submit(apply))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("submit 正常提交设置 PENDING 状态并生成申请单号")
    void submitSuccessSetsPendingAndGeneratesApplyNo() {
        PaymentApply apply = buildApply(null, 0, new BigDecimal("1000"), null);
        when(paymentApplyMapper.insert(any(PaymentApply.class))).thenReturn(1);

        paymentFlowService.submit(apply);

        assertThat(apply.getStatus()).isEqualTo(BusinessConstants.APPROVAL_PENDING);
        assertThat(apply.getApplyNo()).isNotNull().startsWith("PAY");
        assertThat(apply.getApplyTime()).isNotNull();
        verify(paymentApplyMapper).insert(apply);
    }

    // ==================== approve ====================
    @Test
    @DisplayName("approve 申请不存在抛异常")
    void approveNotFoundThrows() {
        when(paymentApplyMapper.selectById(999L)).thenReturn(null);
        assertThatThrownBy(() -> paymentFlowService.approve(999L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("付款申请不存在");
    }

    @Test
    @DisplayName("approve 非待审批状态抛异常")
    void approveWrongStatusThrows() {
        PaymentApply apply = buildApply(1L, BusinessConstants.APPROVAL_APPROVED, new BigDecimal("100"), null);
        when(paymentApplyMapper.selectById(1L)).thenReturn(apply);

        assertThatThrownBy(() -> paymentFlowService.approve(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("当前状态不允许审批通过");
    }

    @Test
    @DisplayName("approve 预算超支抛异常且不修改状态")
    void approveBudgetExceededThrows() {
        PaymentApply apply = buildApply(1L, BusinessConstants.APPROVAL_PENDING, new BigDecimal("100"), 10L);
        when(paymentApplyMapper.selectById(1L)).thenReturn(apply);
        when(budgetControlService.checkBudget(10L, new BigDecimal("100"))).thenReturn(false);

        assertThatThrownBy(() -> paymentFlowService.approve(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("预算超支");

        // 状态不应被修改
        assertThat(apply.getStatus()).isEqualTo(BusinessConstants.APPROVAL_PENDING);
        verify(budgetControlService, never()).updateUsedAmount(anyLong(), any());
    }

    @Test
    @DisplayName("approve 正常通过：校验预算+扣减+流转到 APPROVED")
    void approveSuccess() {
        PaymentApply apply = buildApply(1L, BusinessConstants.APPROVAL_PENDING, new BigDecimal("100"), 10L);
        when(paymentApplyMapper.selectById(1L)).thenReturn(apply);
        when(budgetControlService.checkBudget(10L, new BigDecimal("100"))).thenReturn(true);

        paymentFlowService.approve(1L);

        assertThat(apply.getStatus()).isEqualTo(BusinessConstants.APPROVAL_APPROVED);
        verify(budgetControlService).checkBudget(10L, new BigDecimal("100"));
        verify(budgetControlService).updateUsedAmount(10L, new BigDecimal("100"));
        verify(paymentApplyMapper).updateById(apply);
    }

    @Test
    @DisplayName("approve 无预算计划时跳过预算校验直接通过")
    void approveWithoutBudgetPlanSkipsBudgetCheck() {
        PaymentApply apply = buildApply(1L, BusinessConstants.APPROVAL_PENDING, new BigDecimal("100"), null);
        when(paymentApplyMapper.selectById(1L)).thenReturn(apply);

        paymentFlowService.approve(1L);

        assertThat(apply.getStatus()).isEqualTo(BusinessConstants.APPROVAL_APPROVED);
        verify(budgetControlService, never()).checkBudget(anyLong(), any());
        verify(budgetControlService, never()).updateUsedAmount(anyLong(), any());
    }

    // ==================== reject ====================
    @Test
    @DisplayName("reject 非待审批状态抛异常")
    void rejectWrongStatusThrows() {
        PaymentApply apply = buildApply(1L, BusinessConstants.APPROVAL_APPROVED, new BigDecimal("100"), null);
        when(paymentApplyMapper.selectById(1L)).thenReturn(apply);

        assertThatThrownBy(() -> paymentFlowService.reject(1L, "测试驳回"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("当前状态不允许驳回");
    }

    @Test
    @DisplayName("reject 正常驳回流转到 REJECTED")
    void rejectSuccess() {
        PaymentApply apply = buildApply(1L, BusinessConstants.APPROVAL_PENDING, new BigDecimal("100"), null);
        when(paymentApplyMapper.selectById(1L)).thenReturn(apply);

        paymentFlowService.reject(1L, "金额异常");

        assertThat(apply.getStatus()).isEqualTo(BusinessConstants.APPROVAL_REJECTED);
        verify(paymentApplyMapper).updateById(apply);
    }

    // ==================== markPaid ====================
    @Test
    @DisplayName("markPaid 非已通过状态抛异常")
    void markPaidWrongStatusThrows() {
        PaymentApply apply = buildApply(1L, BusinessConstants.APPROVAL_PENDING, new BigDecimal("100"), null);
        when(paymentApplyMapper.selectById(1L)).thenReturn(apply);

        assertThatThrownBy(() -> paymentFlowService.markPaid(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅已通过的申请可标记已付款");
    }

    @Test
    @DisplayName("markPaid 正常标记已付款流转到 PAID")
    void markPaidSuccess() {
        PaymentApply apply = buildApply(1L, BusinessConstants.APPROVAL_APPROVED, new BigDecimal("100"), null);
        when(paymentApplyMapper.selectById(1L)).thenReturn(apply);

        paymentFlowService.markPaid(1L);

        assertThat(apply.getStatus()).isEqualTo(BusinessConstants.APPROVAL_PAID);
        verify(paymentApplyMapper).updateById(apply);
    }

    // ==================== 完整状态机流转 ====================
    @Test
    @DisplayName("完整流转：PENDING -> APPROVED -> PAID")
    void fullFlowPendingToApprovedToPaid() {
        // 阶段1：提交申请
        PaymentApply apply = buildApply(1L, 0, new BigDecimal("500"), null);
        when(paymentApplyMapper.insert(any(PaymentApply.class))).thenReturn(1);
        paymentFlowService.submit(apply);
        assertThat(apply.getStatus()).isEqualTo(BusinessConstants.APPROVAL_PENDING);

        // 阶段2：审批通过
        when(paymentApplyMapper.selectById(1L)).thenReturn(apply);
        paymentFlowService.approve(1L);
        assertThat(apply.getStatus()).isEqualTo(BusinessConstants.APPROVAL_APPROVED);

        // 阶段3：标记已付款
        paymentFlowService.markPaid(1L);
        assertThat(apply.getStatus()).isEqualTo(BusinessConstants.APPROVAL_PAID);
    }
}
