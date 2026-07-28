package com.finance.platform.data.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.finance.platform.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 额外费用实体
 * <p>
 * 承载物流费、广告费、仓储费等额外成本数据，参与利润核算：
 * <ul>
 *   <li>填了 {@link #orderNo} 的费用：核算时直接计入该订单的 cost_amount</li>
 *   <li>未填 {@link #orderNo} 的费用：进入"公共成本池"，按订单金额占比分摊到周期内所有订单</li>
 * </ul>
 * 币种字段 {@link #currency} 用于多币种支持，{@link #cnyAmount} 由后端按
 * {@link #costDate} 当日汇率折算后回填，保证利润核算币种统一。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("extra_cost")
public class ExtraCost extends BaseEntity {

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 费用类型（见 {@link CostType}） */
    @TableField("cost_type")
    private String costType;

    /** 原始金额 */
    @TableField("amount")
    private BigDecimal amount;

    /** 币种 */
    @TableField("currency")
    private String currency;

    /** 人民币金额（折算后，由后端按 costDate 当日汇率回填） */
    @TableField("cny_amount")
    private BigDecimal cnyAmount;

    /** 核算周期，如 202607 */
    @TableField("period")
    private String period;

    /** 关联订单号（空则进入公共成本池分摊） */
    @TableField("order_no")
    private String orderNo;

    /** 收款方 */
    @TableField("payee")
    private String payee;

    /** 费用发生日期 */
    @TableField("cost_date")
    private LocalDate costDate;

    /** 备注 */
    @TableField("remark")
    private String remark;

    /** 来源：IMPORT/MANUAL */
    @TableField("source")
    private String source;

    /** 导入批次号 */
    @TableField("batch_no")
    private String batchNo;

    /** 状态：0 已作废，1 生效 */
    @TableField("status")
    private Integer status;
}
