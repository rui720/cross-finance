package com.finance.platform.accounting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.finance.platform.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 费用分摊规则配置实体
 * <p>
 * 定义成本池在不同订单间的分摊方式，ruleType 取值 WEIGHT（按重量/数量）或 AMOUNT（按金额），
 * formula 以 JSON 描述分摊公式参数，enabled 控制规则是否启用。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cost_allocation_rule")
public class CostAllocationRule extends BaseEntity {

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 规则名 */
    @TableField("rule_name")
    private String ruleName;

    /** 类型：WEIGHT / AMOUNT */
    @TableField("rule_type")
    private String ruleType;

    /** 是否启用：0 禁用，1 启用 */
    @TableField("enabled")
    private Integer enabled;

    /** 描述 */
    @TableField("description")
    private String description;

    /** 分摊公式（JSON） */
    @TableField("formula")
    private String formula;
}
