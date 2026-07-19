package com.finance.platform.fund.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.finance.platform.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 付款申请单实体
 * <p>
 * 记录收款方、金额、事由及关联预算等信息，状态字段遵循 BusinessConstants.APPROVAL_* 状态机：
 * DRAFT(0) -> PENDING(1) -> APPROVED(2) -> PAID(4)，或 PENDING(1) -> REJECTED(3)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("payment_apply")
public class PaymentApply extends BaseEntity {

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 申请单号 */
    @TableField("apply_no")
    private String applyNo;

    /** 收款方 */
    @TableField("payee")
    private String payee;

    /** 收款账号 */
    @TableField("bank_account")
    private String bankAccount;

    /** 币种 */
    @TableField("currency")
    private String currency;

    /** 金额 */
    @TableField("amount")
    private BigDecimal amount;

    /** 付款事由 */
    @TableField("apply_reason")
    private String applyReason;

    /** 状态：参考 BusinessConstants.APPROVAL_* */
    @TableField("status")
    private Integer status;

    /** 申请人 ID */
    @TableField("applicant_id")
    private Long applicantId;

    /** 申请时间 */
    @TableField("apply_time")
    private LocalDateTime applyTime;

    /** 关联预算 ID */
    @TableField("budget_plan_id")
    private Long budgetPlanId;
}
