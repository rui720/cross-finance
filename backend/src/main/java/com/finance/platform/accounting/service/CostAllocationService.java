package com.finance.platform.accounting.service;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 费用分摊服务接口
 * <p>
 * 根据分摊策略类型，将总成本分摊到周期内各订单，返回订单号到分摊成本的映射。
 */
public interface CostAllocationService {

    /**
     * 按策略分摊总成本
     *
     * @param period       核算周期
     * @param totalCost    待分摊的总成本
     * @param strategyType 策略类型（WEIGHT / AMOUNT）
     * @return 订单号 -> 分摊成本
     */
    Map<String, BigDecimal> allocate(String period, BigDecimal totalCost, String strategyType);
}
