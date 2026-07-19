package com.finance.platform.accounting.strategy;

import com.finance.platform.data.entity.RawOrder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 按重量（数量）分摊策略
 * <p>
 * 以订单数量近似“重量”，每笔订单权重相同，总成本在订单间均分；
 * 最后一笔吸收除法尾差，保证各订单分摊之和等于总成本。
 */
@Component
public class WeightAllocationStrategy implements AllocationStrategy {

    private static final String TYPE = "WEIGHT";

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public BigDecimal allocate(BigDecimal totalCost, List<RawOrder> orders, int index) {
        int size = orders.size();
        if (size <= 0 || totalCost == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal base = totalCost.divide(BigDecimal.valueOf(size), 2, RoundingMode.HALF_UP);
        if (index < size - 1) {
            return base;
        }
        // 最后一笔吸收尾差，确保分摊总和等于总成本
        BigDecimal allocatedBefore = base.multiply(BigDecimal.valueOf(size - 1L));
        return totalCost.subtract(allocatedBefore);
    }
}
