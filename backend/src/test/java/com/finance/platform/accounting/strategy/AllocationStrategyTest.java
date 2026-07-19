package com.finance.platform.accounting.strategy;

import com.finance.platform.data.entity.RawOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 费用分摊策略单元测试
 * <p>
 * 验证两种分摊策略的核心逻辑：
 * 1. 分摊总和等于总成本（尾差吸收）
 * 2. 按金额占比分摊的正确性
 * 3. 除零退化（金额总和为 0 时均分）
 * 4. 空列表安全返回 0
 */
@DisplayName("费用分摊策略测试")
class AllocationStrategyTest {

    private RawOrder buildOrder(String orderNo, BigDecimal amount) {
        RawOrder o = new RawOrder();
        o.setOrderNo(orderNo);
        o.setAmount(amount);
        return o;
    }

    /** 计算所有订单分摊之和 */
    private BigDecimal sumAllocation(AllocationStrategy strategy, BigDecimal totalCost, List<RawOrder> orders) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < orders.size(); i++) {
            sum = sum.add(strategy.allocate(totalCost, orders, i));
        }
        return sum;
    }

    // ==================== 按金额分摊策略 ====================
    @Nested
    @DisplayName("AmountAllocationStrategy 按金额分摊")
    class AmountStrategyTest {

        private final AmountAllocationStrategy strategy = new AmountAllocationStrategy();

        @Test
        @DisplayName("分摊总和应等于总成本（尾差吸收）")
        void sumShouldEqualTotalCost() {
            List<RawOrder> orders = Arrays.asList(
                    buildOrder("O001", new BigDecimal("100")),
                    buildOrder("O002", new BigDecimal("200")),
                    buildOrder("O003", new BigDecimal("300"))
            );
            BigDecimal totalCost = new BigDecimal("100.00");

            BigDecimal sum = sumAllocation(strategy, totalCost, orders);

            assertThat(sum).isEqualByComparingTo(totalCost);
        }

        @Test
        @DisplayName("金额越大的订单分摊越多")
        void biggerAmountGetsMoreCost() {
            List<RawOrder> orders = Arrays.asList(
                    buildOrder("SMALL", new BigDecimal("100")),
                    buildOrder("BIG", new BigDecimal("900"))
            );
            BigDecimal totalCost = new BigDecimal("1000.00");

            BigDecimal smallShare = strategy.allocate(totalCost, orders, 0);
            BigDecimal bigShare = strategy.allocate(totalCost, orders, 1);

            assertThat(bigShare).isGreaterThan(smallShare);
            // 100:900 比例下，小额应分到约 100，大额约 900
            assertThat(smallShare).isEqualByComparingTo(new BigDecimal("100.00"));
        }

        @Test
        @DisplayName("金额总和为 0 时退化为均分")
        void zeroSumFallsBackToEvenSplit() {
            List<RawOrder> orders = Arrays.asList(
                    buildOrder("O1", BigDecimal.ZERO),
                    buildOrder("O2", BigDecimal.ZERO),
                    buildOrder("O3", BigDecimal.ZERO)
            );
            BigDecimal totalCost = new BigDecimal("99.00");

            BigDecimal sum = sumAllocation(strategy, totalCost, orders);

            assertThat(sum).isEqualByComparingTo(totalCost);
            // 前两笔均分 33.00，最后一笔吸收尾差 33.00
            assertThat(strategy.allocate(totalCost, orders, 0)).isEqualByComparingTo(new BigDecimal("33.00"));
            assertThat(strategy.allocate(totalCost, orders, 2)).isEqualByComparingTo(new BigDecimal("33.00"));
        }

        @Test
        @DisplayName("空列表返回 0")
        void emptyListReturnsZero() {
            BigDecimal result = strategy.allocate(new BigDecimal("100"), Collections.emptyList(), 0);
            assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("totalCost 为 null 返回 0")
        void nullCostReturnsZero() {
            List<RawOrder> orders = Collections.singletonList(buildOrder("O1", new BigDecimal("100")));
            BigDecimal result = strategy.allocate(null, orders, 0);
            assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("getType 返回 AMOUNT")
        void getTypeReturnsAmount() {
            assertThat(strategy.getType()).isEqualTo("AMOUNT");
        }
    }

    // ==================== 按重量（数量）分摊策略 ====================
    @Nested
    @DisplayName("WeightAllocationStrategy 按数量均分")
    class WeightStrategyTest {

        private final WeightAllocationStrategy strategy = new WeightAllocationStrategy();

        @Test
        @DisplayName("均分：每笔订单分摊相同（最后一笔吸收尾差）")
        void evenSplit() {
            List<RawOrder> orders = Arrays.asList(
                    buildOrder("O1", new BigDecimal("100")),
                    buildOrder("O2", new BigDecimal("200")),
                    buildOrder("O3", new BigDecimal("300"))
            );
            BigDecimal totalCost = new BigDecimal("100.00");

            BigDecimal share1 = strategy.allocate(totalCost, orders, 0);
            BigDecimal share2 = strategy.allocate(totalCost, orders, 1);
            BigDecimal share3 = strategy.allocate(totalCost, orders, 2);

            // 前两笔均分 33.33
            assertThat(share1).isEqualByComparingTo(new BigDecimal("33.33"));
            assertThat(share2).isEqualByComparingTo(new BigDecimal("33.33"));
            // 最后一笔吸收尾差
            assertThat(share1.add(share2).add(share3)).isEqualByComparingTo(totalCost);
        }

        @Test
        @DisplayName("单笔订单承担全部成本")
        void singleOrderTakesAll() {
            List<RawOrder> orders = Collections.singletonList(buildOrder("ONLY", new BigDecimal("500")));
            BigDecimal totalCost = new BigDecimal("100.00");

            BigDecimal share = strategy.allocate(totalCost, orders, 0);

            assertThat(share).isEqualByComparingTo(totalCost);
        }

        @Test
        @DisplayName("空列表返回 0")
        void emptyListReturnsZero() {
            BigDecimal result = strategy.allocate(new BigDecimal("100"), Collections.emptyList(), 0);
            assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("getType 返回 WEIGHT")
        void getTypeReturnsWeight() {
            assertThat(strategy.getType()).isEqualTo("WEIGHT");
        }
    }
}
