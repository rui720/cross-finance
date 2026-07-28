package com.finance.platform.accounting.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 利润明细汇总视图
 * <p>
 * 在当前筛选条件（平台/店铺/币种/对账状态）下对所有记录进行金额合计，
 * 用于利润明细表格底部合计行。
 * <p>
 * 注意：账单金额、平台手续费等取自 profit_report 的 cny_amount / fee_cost，
 * 而银行到账、中转费、对账差值需要按订单号关联实时计算，
 * 因此本汇总仅覆盖利润核算字段（取自 profit_report），银行侧字段不参与合计。
 */
@Data
public class ProfitDetailSummaryVO {

    /** 筛选后订单数 */
    private Long orderCount;

    /** 账单金额合计（CNY） */
    private BigDecimal totalBillAmount;

    /** 平台手续费合计（CNY） */
    private BigDecimal totalPlatformFee;

    /** 公共分摊合计（CNY） */
    private BigDecimal totalSharedCost;

    /** 直接成本合计（CNY） */
    private BigDecimal totalDirectCost;

    /** 总成本合计（CNY） */
    private BigDecimal totalCostAmount;

    /** 利润合计（CNY） */
    private BigDecimal totalProfitAmount;

    /** 利润率 = 利润合计 / 账单金额合计 */
    private BigDecimal profitRate;
}
