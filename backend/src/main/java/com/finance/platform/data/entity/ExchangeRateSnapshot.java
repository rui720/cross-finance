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
 * 汇率快照实体
 * <p>
 * 记录某一日的源币种到目标币种汇率及来源（央行/第三方），
 * 供数据清洗换算与定时任务刷新内存汇率表使用。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exchange_rate_snapshot")
public class ExchangeRateSnapshot extends BaseEntity {

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 汇率日期 */
    @TableField("rate_date")
    private LocalDate rateDate;

    /** 源币种 */
    @TableField("from_currency")
    private String fromCurrency;

    /** 目标币种 */
    @TableField("to_currency")
    private String toCurrency;

    /** 汇率 */
    @TableField("rate")
    private BigDecimal rate;

    /** 来源：央行/第三方 */
    @TableField("source")
    private String source;
}
