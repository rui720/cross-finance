package com.finance.platform.data.entity;

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
 * 平台账单与银行流水统一存储，通过 source 字段区分数据来源，batch_no 标识导入批次。
 * <p>
 * 字段映射已迁移至 {@link ImportTemplate} 表配置化管理，
 * 不再使用 {@code @ExcelProperty} 注解硬编码列名。
 * <p>
 * 清洗状态字段：
 * - cleanStatus: 0 未清洗，1 已清洗，2 清洗失败
 * - cleanErrors: 行级错误信息
 * - cleanTime: 清洗完成时间
 * - reconcileStatus: 对账状态（0 未对账/1 已完成/2 对账失败/3 未到账/4 不明入账），与 settleTime 语义分离
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("raw_order")
public class RawOrder extends BaseEntity {

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

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

    /** 金额 */
    @TableField("amount")
    private BigDecimal amount;

    /** 平台费 */
    @TableField("fee")
    private BigDecimal fee;

    /** 结算金额 */
    @TableField("settle_amount")
    private BigDecimal settleAmount;

    /** 下单时间 */
    @TableField("order_time")
    private LocalDateTime orderTime;

    /** 结算时间（原始数据中的，清洗不再覆盖） */
    @TableField("settle_time")
    private LocalDateTime settleTime;

    /** 数据来源（平台/银行/手工） */
    @TableField("source")
    private String source;

    /** 导入批次号 */
    @TableField("batch_no")
    private String batchNo;

    /** 清洗状态：0 未清洗，1 已清洗，2 清洗失败 */
    @TableField("clean_status")
    private Integer cleanStatus;

    /** 清洗错误信息（行级） */
    @TableField("clean_errors")
    private String cleanErrors;

    /** 清洗完成时间 */
    @TableField("clean_time")
    private LocalDateTime cleanTime;

    /** 对账状态：0 未对账，1 已完成，2 对账失败，3 未到账，4 不明入账 */
    @TableField("reconcile_status")
    private Integer reconcileStatus;

    /** 匹配的对方记录ID */
    @TableField("reconcile_match_id")
    private Long reconcileMatchId;

    /** 对账差值（平台应收-银行到账，CNY） */
    @TableField("reconcile_diff")
    private BigDecimal reconcileDiff;

    /** 清洗状态常量 */
    public static final int CLEAN_STATUS_NONE = 0;
    public static final int CLEAN_STATUS_DONE = 1;
    public static final int CLEAN_STATUS_FAIL = 2;

    /** 对账状态常量（用扩展的 reconcile_status 值替代原 reconcile_type 语义） */
    public static final int RECONCILE_NONE = 0;        // 未对账
    public static final int RECONCILE_DONE = 1;         // 已完成（金额一致）
    public static final int RECONCILE_FAIL = 2;         // 对账失败（金额不一致）
    public static final int RECONCILE_UNRECEIVED = 3;   // 未到账（平台有记录银行无）
    public static final int RECONCILE_UNKNOWN = 4;      // 不明入账（银行有记录平台无）
}
