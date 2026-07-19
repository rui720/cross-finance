package com.finance.platform.accounting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.finance.platform.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 利润报表实体
 * <p>
 * 核算引擎按周期对每笔订单产出的最终利润明细，包含原始金额、折算人民币金额、
 * 分摊成本、利润及利润率，是利润归因分析与报表展示的底层数据。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("profit_report")
public class ProfitReport extends BaseEntity {

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 核算周期，如 202607 */
    @TableField("period")
    private String period;

    /** 订单号 */
    @TableField("order_no")
    private String orderNo;

    /** 平台 */
    @TableField("platform")
    private String platform;

    /** 币种 */
    @TableField("currency")
    private String currency;

    /** 原始金额 */
    @TableField("original_amount")
    private BigDecimal originalAmount;

    /** 人民币金额（折算后） */
    @TableField("cny_amount")
    private BigDecimal cnyAmount;

    /** 分摊成本 */
    @TableField("cost_amount")
    private BigDecimal costAmount;

    /** 利润 */
    @TableField("profit_amount")
    private BigDecimal profitAmount;

    /** 利润率 */
    @TableField("profit_rate")
    private BigDecimal profitRate;

    /** 分摊规则 ID */
    @TableField("rule_id")
    private Long ruleId;
}
