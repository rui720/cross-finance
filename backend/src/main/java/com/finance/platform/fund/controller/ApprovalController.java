package com.finance.platform.fund.controller;

import com.finance.platform.common.core.Result;
import com.finance.platform.fund.service.PaymentFlowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 付款审批接口
 * <p>
 * 提供审批通过、驳回、标记已付款能力，驱动付款申请单状态流转。
 * 权限：
 * - 审批通过/驳回：ADMIN / APPROVER
 * - 标记已付款：ADMIN / CASHIER
 */
@Slf4j
@RestController
@RequestMapping("/fund/approval")
@RequiredArgsConstructor
public class ApprovalController {

    private final PaymentFlowService paymentFlowService;

    /**
     * 审批通过（仅审批经理/管理员）
     */
    @PostMapping("/approve/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','APPROVER')")
    public Result<Void> approve(@PathVariable Long id) {
        paymentFlowService.approve(id);
        return Result.success();
    }

    /**
     * 审批驳回（仅审批经理/管理员）
     *
     * @param reason 驳回原因
     */
    @PostMapping("/reject/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','APPROVER')")
    public Result<Void> reject(@PathVariable Long id, @RequestParam(required = false) String reason) {
        paymentFlowService.reject(id, reason);
        return Result.success();
    }

    /**
     * 标记已付款（仅出纳/管理员）
     */
    @PostMapping("/mark-paid/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','CASHIER')")
    public Result<Void> markPaid(@PathVariable Long id) {
        paymentFlowService.markPaid(id);
        return Result.success();
    }
}

