package com.finance.platform.fund.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finance.platform.common.core.LoginUser;
import com.finance.platform.common.core.Result;
import com.finance.platform.fund.entity.PaymentApply;
import com.finance.platform.fund.service.PaymentFlowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;

/**
 * 付款申请接口
 * <p>
 * 提供发起付款申请、查询申请列表、查询申请详情能力。
 * 权限：ADMIN / FINANCE / APPROVER / OPERATOR 可访问。
 * 数据范围：非管理员/财务角色只能查看自己发起的申请。
 */
@Slf4j
@RestController
@RequestMapping("/fund/payment")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','FINANCE','APPROVER','OPERATOR')")
public class PaymentApplyController {

    private final PaymentFlowService paymentFlowService;

    /**
     * 发起付款申请
     */
    @PostMapping("/apply")
    public Result<Void> apply(@RequestBody PaymentApply apply) {
        paymentFlowService.submit(apply);
        return Result.success();
    }

    /**
     * 分页查询付款申请列表
     * <p>
     * 数据范围：
     * - ADMIN / FINANCE：可看全部
     * - APPROVER / OPERATOR：仅看自己发起的申请（applicant_id = 当前用户ID）
     *
     * @param status      可选状态过滤
     * @param applicantId 可选申请人过滤（管理员可指定任意人，非管理员强制覆盖为当前用户）
     */
    @GetMapping("/page")
    public Result<Page<PaymentApply>> page(@RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "10") long size,
                                            @RequestParam(required = false) Integer status,
                                            @RequestParam(required = false) Long applicantId) {
        Long currentUserId = currentUserId();
        boolean isAdminOrFinance = hasAnyRole("ADMIN", "FINANCE");
        // 非管理员/财务：强制只查自己
        Long effectiveApplicantId = isAdminOrFinance ? applicantId : currentUserId;

        Page<PaymentApply> p = new Page<>(page, size);
        paymentFlowService.page(p, new LambdaQueryWrapper<PaymentApply>()
                .eq(status != null, PaymentApply::getStatus, status)
                .eq(effectiveApplicantId != null, PaymentApply::getApplicantId, effectiveApplicantId)
                .orderByDesc(PaymentApply::getId));
        return Result.success(p);
    }

    /**
     * 查询付款申请详情
     */
    @GetMapping("/{id}")
    public Result<PaymentApply> detail(@PathVariable Long id) {
        return Result.success(paymentFlowService.getById(id));
    }

    /**
     * 从 SecurityContext 获取当前登录用户 ID
     */
    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof LoginUser loginUser) {
            return loginUser.userId();
        }
        return null;
    }

    /**
     * 判断当前用户是否拥有任意一个指定角色
     */
    private boolean hasAnyRole(String... roles) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        Collection<? extends GrantedAuthority> authorities = auth.getAuthorities();
        if (authorities == null) return false;
        for (String role : roles) {
            String authority = role.startsWith("ROLE_") ? role : "ROLE_" + role;
            for (GrantedAuthority ga : authorities) {
                if (authority.equals(ga.getAuthority())) return true;
            }
        }
        return false;
    }
}

