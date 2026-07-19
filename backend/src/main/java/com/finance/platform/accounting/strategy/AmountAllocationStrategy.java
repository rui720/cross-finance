package com.finance.platform.accounting.strategy;

import com.finance.platform.data.entity.RawOrder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 按金额分摊策略
 * <p>
 * 以订单金额占比分摊总成本，金额越大的订单承担成本越多；
 * 最后一笔吸收除法尾差，保证各订单分摊之和等于总成本。
 * 当周期内订单金额总和为 0 时退化为均分，避免除零。
 */
@Component
public class AmountAllocationStrategy implements AllocationStrategy {

    private static final String TYPE = "AMOUNT";

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
        BigDecimal sum = BigDecimal.ZERO;
        for (RawOrder o : orders) {
            sum = sum.add(o.getAmount() == null ? BigDecimal.ZERO : o.getAmount());
        }
        if (sum.compareTo(BigDecimal.ZERO) == 0) {
            // 金额总和为 0 时退化为均分
            BigDecimal base = totalCost.divide(BigDecimal.valueOf(size), 2, RoundingMode.HALF_UP);
            if (index < size - 1) {
                return base;
            }
            return totalCost.subtract(base.multiply(BigDecimal.valueOf(size - 1L)));
        }
        if (index < size - 1) {
            return share(totalCost, amountOf(orders.get(index)), sum);
        }
        // 最后一笔吸收尾差
        BigDecimal allocatedBefore = BigDecimal.ZERO;
        for (int i = 0; i < size - 1; i++) {
            allocatedBefore = allocatedBefore.add(share(totalCost, amountOf(orders.get(i)), sum));
        }
        return totalCost.subtract(allocatedBefore);
    }

    /** 计算单笔订单的分摊基数 */
    private BigDecimal share(BigDecimal totalCost, BigDecimal orderAmount, BigDecimal sum) {
        return totalCost.multiply(orderAmount).divide(sum, 2, RoundingMode.HALF_UP);
    }

    /** 安全读取订单金额，空值按 0 处理 */
    private BigDecimal amountOf(RawOrder order) {
        return order.getAmount() == null ? BigDecimal.ZERO : order.getAmount();
    }
}
