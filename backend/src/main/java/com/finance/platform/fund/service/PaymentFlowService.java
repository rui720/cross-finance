package com.finance.platform.fund.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.finance.platform.fund.entity.PaymentApply;

/**
 * 付款状态流转服务接口
 * <p>
 * 管理付款申请单的状态机：DRAFT -> PENDING -> APPROVED -> PAID，或 PENDING -> REJECTED。
 * 非法状态转换抛出 BusinessException。
 */
public interface PaymentFlowService extends IService<PaymentApply> {

    /**
     * 提交付款申请：生成单号、置为待审批
     *
     * @param apply 付款申请单
     */
    void submit(PaymentApply apply);

    /**
     * 审批通过：校验预算后将状态置为已通过并扣减预算
     *
     * @param id 申请单 ID
     */
    void approve(Long id);

    /**
     * 审批驳回：将状态置为已驳回
     *
     * @param id     申请单 ID
     * @param reason 驳回原因
     */
    void reject(Long id, String reason);

    /**
     * 标记已付款：将状态置为已付款
     *
     * @param id 申请单 ID
     */
    void markPaid(Long id);
}
