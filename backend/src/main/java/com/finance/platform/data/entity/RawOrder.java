package com.finance.platform.data.entity;

import com.alibaba.excel.annotation.ExcelProperty;
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
 * 原始业务订单实体
 * <p>
 * 平台账单与银行流水统一存储，通过 source 字段区分数据来源，
 * batch_no 标识导入批次，使用 EasyExcel 注解映射导入列。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("raw_order")
public class RawOrder extends BaseEntity {

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 订单号 */
    @ExcelProperty("订单号")
    @TableField("order_no")
    private String orderNo;

    /** 平台 */
    @ExcelProperty("平台")
    @TableField("platform")
    private String platform;

    /** 店铺 ID */
    @ExcelProperty("店铺ID")
    @TableField("shop_id")
    private String shopId;

    /** 币种 */
    @ExcelProperty("币种")
    @TableField("currency")
    private String currency;

    /** 金额 */
    @ExcelProperty("金额")
    @TableField("amount")
    private BigDecimal amount;

    /** 平台费 */
    @ExcelProperty("平台费")
    @TableField("fee")
    private BigDecimal fee;

    /** 结算金额 */
    @ExcelProperty("结算金额")
    @TableField("settle_amount")
    private BigDecimal settleAmount;

    /** 下单时间 */
    @ExcelProperty("下单时间")
    @TableField("order_time")
    private LocalDateTime orderTime;

    /** 结算时间 */
    @ExcelProperty("结算时间")
    @TableField("settle_time")
    private LocalDateTime settleTime;

    /** 数据来源（平台/银行/手工） */
    @TableField("source")
    private String source;

    /** 导入批次号 */
    @TableField("batch_no")
    private String batchNo;
}
