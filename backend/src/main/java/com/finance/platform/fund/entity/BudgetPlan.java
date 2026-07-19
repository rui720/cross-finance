package com.finance.platform.fund.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.finance.platform.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 预算计划实体
 * <p>
 * 记录某部门某周期的预算总额、已使用金额及预警阈值（百分比，如 80 表示 80%），
 * 付款审批时据此进行超支校验与预警。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("budget_plan")
public class BudgetPlan extends BaseEntity {

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 计划名 */
    @TableField("plan_name")
    private String planName;

    /** 预算周期 */
    @TableField("period")
    private String period;

    /** 预算总额 */
    @TableField("total_amount")
    private BigDecimal totalAmount;

    /** 已使用金额 */
    @TableField("used_amount")
    private BigDecimal usedAmount;

    /** 币种 */
    @TableField("currency")
    private String currency;

    /** 部门 ID */
    @TableField("dept_id")
    private Long deptId;

    /** 预警阈值百分比，如 80 表示 80% */
    @TableField("warning_threshold")
    private BigDecimal warningThreshold;
}
