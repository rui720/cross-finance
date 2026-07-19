package com.finance.platform.accounting.strategy;

import com.finance.platform.data.entity.RawOrder;

import java.math.BigDecimal;
import java.util.List;

/**
 * 费用分摊策略接口
 * <p>
 * 不同实现代表不同的成本分摊维度（按重量/数量、按金额等），
 * 由 {@code CostAllocationService} 按 {@link #getType()} 选择具体策略。
 */
public interface AllocationStrategy {

    /**
     * 获取策略类型标识，如 WEIGHT、AMOUNT
     *
     * @return 策略类型
     */
    String getType();

    /**
     * 计算指定订单应分摊的成本
     *
     * @param totalCost 待分摊的总成本
     * @param orders    当前周期内的全部订单
     * @param index     当前订单在 orders 中的下标
     * @return 当前订单的分摊成本
     */
    BigDecimal allocate(BigDecimal totalCost, List<RawOrder> orders, int index);
}
