package com.finance.platform.accounting.entity;

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
 * 利润报表实体
 * <p>
 * 核算引擎按周期对每笔订单产出的最终利润明细，包含原始金额、折算人民币金额、
 * 成本拆分（平台费/公共分摊/直接成本）、利润及利润率，以及对账状态与实际到账金额，
 * 是利润归因分析与报表展示的底层数据。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("profit_report")
public class ProfitReport extends BaseEntity {

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 核算周期：月份模式如 202607，日期范围模式如 2026-08-15~2026-09-15 */
    @TableField("period")
    private String period;

    /** 订单号 */
    @TableField("order_no")
    private String orderNo;

    /** 平台 */
    @TableField("platform")
    private String platform;

    /** 店铺 ID */
    @TableField("shop_id")
    private String shopId;

    /** 币种 */
    @TableField("currency")
    private String currency;

    /** 原始金额 */
    @TableField("original_amount")
    private BigDecimal originalAmount;

    /** 人民币金额（折算后） */
    @TableField("cny_amount")
    private BigDecimal cnyAmount;

    /** 平台费（该订单自身的平台费，直接归属） */
    @TableField("fee_cost")
    private BigDecimal feeCost;

    /** 公共分摊成本（无订单号的额外费用按策略分摊到该订单的部分） */
    @TableField("shared_cost")
    private BigDecimal sharedCost;

    /** 直接成本（按订单号归集的额外费用） */
    @TableField("direct_cost")
    private BigDecimal directCost;

    /** 总成本 = fee_cost + shared_cost + direct_cost */
    @TableField("cost_amount")
    private BigDecimal costAmount;

    /** 利润 */
    @TableField("profit_amount")
    private BigDecimal profitAmount;

    /** 利润率 */
    @TableField("profit_rate")
    private BigDecimal profitRate;

    /** 订单时间（用于日/周趋势聚合） */
    @TableField("order_time")
    private LocalDateTime orderTime;

    /** 对账状态：0 未对账，1 已完成，2 对账失败，3 未到账，4 不明入账 */
    @TableField("reconcile_status")
    private Integer reconcileStatus;

    /** 实际到账金额（CNY，取自银行流水匹配金额；未到账为 0） */
    @TableField("actual_received_amount")
    private BigDecimal actualReceivedAmount;

    /** 分摊规则 ID */
    @TableField("rule_id")
    private Long ruleId;
}
