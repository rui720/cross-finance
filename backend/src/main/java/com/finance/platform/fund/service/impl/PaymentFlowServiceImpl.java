package com.finance.platform.fund.service.impl;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.finance.platform.common.constant.BusinessConstants;
import com.finance.platform.common.exception.BusinessException;
import com.finance.platform.fund.entity.PaymentApply;
import com.finance.platform.fund.mapper.PaymentApplyMapper;
import com.finance.platform.fund.service.BudgetControlService;
import com.finance.platform.fund.service.PaymentFlowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.Objects;

/**
 * 付款状态流转实现
 * <p>
 * 采用状态机模式：每个动作校验前置状态合法性，非法转换抛 BusinessException；
 * approve 通过预算校验后扣减预算已使用金额。申请单号采用 时间戳 + 随机数 生成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentFlowServiceImpl extends ServiceImpl<PaymentApplyMapper, PaymentApply> implements PaymentFlowService {

    private final PaymentApplyMapper paymentApplyMapper;
    private final BudgetControlService budgetControlService;

    @Override
    public void submit(PaymentApply apply) {
        if (apply.getAmount() == null || apply.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("付款金额必须大于 0");
        }
        apply.setApplyNo(generateApplyNo());
        apply.setStatus(BusinessConstants.APPROVAL_PENDING);
        apply.setApplyTime(LocalDateTime.now());
        paymentApplyMapper.insert(apply);
        log.info("[付款] 提交申请 applyNo={} applicantId={}", apply.getApplyNo(), apply.getApplicantId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long id) {
        PaymentApply apply = paymentApplyMapper.selectById(id);
        if (apply == null) {
            throw new BusinessException("付款申请不存在: " + id);
        }
        if (!Objects.equals(apply.getStatus(), BusinessConstants.APPROVAL_PENDING)) {
            throw new BusinessException("当前状态不允许审批通过: " + apply.getStatus());
        }
        // 校验预算，通过后原子扣减已使用金额（并发安全）
        if (apply.getBudgetPlanId() != null) {
            boolean ok = budgetControlService.checkBudget(apply.getBudgetPlanId(), apply.getAmount());
            if (!ok) {
                throw new BusinessException("预算超支，审批不通过");
            }
            budgetControlService.updateUsedAmount(apply.getBudgetPlanId(), apply.getAmount());
        }
        apply.setStatus(BusinessConstants.APPROVAL_APPROVED);
        paymentApplyMapper.updateById(apply);
        log.info("[付款] 审批通过 id={} applyNo={}", id, apply.getApplyNo());
    }

    @Override
    public void reject(Long id, String reason) {
        PaymentApply apply = paymentApplyMapper.selectById(id);
        if (apply == null) {
            throw new BusinessException("付款申请不存在: " + id);
        }
        if (!Objects.equals(apply.getStatus(), BusinessConstants.APPROVAL_PENDING)) {
            throw new BusinessException("当前状态不允许驳回: " + apply.getStatus());
        }
        apply.setStatus(BusinessConstants.APPROVAL_REJECTED);
        paymentApplyMapper.updateById(apply);
        log.info("[付款] 审批驳回 id={} applyNo={} reason={}", id, apply.getApplyNo(), reason);
    }

    @Override
    public void markPaid(Long id) {
        PaymentApply apply = paymentApplyMapper.selectById(id);
        if (apply == null) {
            throw new BusinessException("付款申请不存在: " + id);
        }
        if (!Objects.equals(apply.getStatus(), BusinessConstants.APPROVAL_APPROVED)) {
            throw new BusinessException("仅已通过的申请可标记已付款，当前状态: " + apply.getStatus());
        }
        apply.setStatus(BusinessConstants.APPROVAL_PAID);
        paymentApplyMapper.updateById(apply);
        log.info("[付款] 标记已付款 id={} applyNo={}", id, apply.getApplyNo());
    }

    /** 生成申请单号：PAY + 时间戳 + 4 位随机数 */
    private String generateApplyNo() {
        return "PAY" + DateUtil.format(new Date(), "yyyyMMddHHmmss") + RandomUtil.randomNumbers(4);
    }
}
