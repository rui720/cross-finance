package com.finance.platform.accounting.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 利润明细聚合视图
 * <p>
 * 以平台账单为主体，按订单号关联银行流水、中转费与利润核算结果，一行展示完整利润明细。
 * 将账单金额、平台手续费、中转费、银行到账等关键金额列集中展示，方便比对。
 * <p>
 * 非 CNY 币种的金额均按订单当日汇率折算为 CNY，折算口径与对账计算一致。
 */
@Data
public class ProfitDetailVO {

    /** 平台账单记录 ID（用于操作） */
    private Long id;

    /** 订单号 */
    private String orderNo;

    /** 平台 */
    private String platform;

    /** 店铺 ID */
    private String shopId;

    /** 币种 */
    private String currency;

    /** 订单时间 */
    private LocalDateTime orderTime;

    /** 批次号 */
    private String batchNo;

    /** 对账状态：0未对账/1已完成/2对账失败/3未到账/4不明入账 */
    private Integer reconcileStatus;

    // ==================== 金额列（集中展示，方便比对） ====================

    /** 账单金额（CNY）：平台应收买家总额，按订单当日汇率折算 */
    private BigDecimal platformAmountCny;

    /** 平台手续费（CNY）：raw_order.fee 按订单当日汇率折算 */
    private BigDecimal platformFeeCny;

    /** 中转费（CNY）：extra_cost 中 TRANSFER_FEE 类型的 cny_amount 合计 */
    private BigDecimal transferFeeCny;

    /** 银行到账（CNY）：银行流水 amount 折算 CNY；未到账为 null */
    private BigDecimal bankReceivedCny;

    /** 公共分摊成本（CNY） */
    private BigDecimal sharedCost;

    /** 直接成本（CNY） */
    private BigDecimal directCost;

    /** 总成本（CNY）= 平台费 + 公共分摊 + 直接成本 */
    private BigDecimal costAmount;

    /** 利润（CNY）= 账单金额 - 总成本 */
    private BigDecimal profitAmount;

    /** 利润率 = 利润 / 账单金额 */
    private BigDecimal profitRate;

    /** 实际到账金额（CNY）：取自 profit_report.actual_received_amount */
    private BigDecimal actualReceivedAmount;

    /** 对账差值（CNY）= 账单金额 - 银行到账 - 中转费 - 平台手续费 */
    private BigDecimal reconcileDiff;
}
