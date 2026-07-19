package com.finance.platform.accounting.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.platform.accounting.service.CostAllocationService;
import com.finance.platform.accounting.strategy.AllocationStrategy;
import com.finance.platform.common.exception.BusinessException;
import com.finance.platform.data.entity.RawOrder;
import com.finance.platform.data.mapper.RawOrderMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 费用分摊服务实现
 * <p>
 * Spring 自动注入所有 {@link AllocationStrategy} 实现，按 {@code getType()} 建立索引；
 * 调用 allocate 时根据 strategyType 选定策略，遍历周期内订单逐笔计算分摊成本。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CostAllocationServiceImpl implements CostAllocationService {

    private final List<AllocationStrategy> strategies;
    private final RawOrderMapper rawOrderMapper;

    /** 策略索引：type -> strategy */
    private Map<String, AllocationStrategy> strategyMap;

    @PostConstruct
    public void init() {
        strategyMap = new HashMap<>();
        if (strategies != null) {
            for (AllocationStrategy strategy : strategies) {
                strategyMap.put(strategy.getType(), strategy);
            }
        }
        log.info("[分摊] 已加载分摊策略: {}", strategyMap.keySet());
    }

    @Override
    public Map<String, BigDecimal> allocate(String period, BigDecimal totalCost, String strategyType) {
        AllocationStrategy strategy = strategyMap.get(strategyType);
        if (strategy == null) {
            throw new BusinessException("不支持的分摊策略类型: " + strategyType);
        }
        List<RawOrder> orders = rawOrderMapper.selectList(new LambdaQueryWrapper<RawOrder>()
                .apply("DATE_FORMAT(order_time, '%Y%m') = {0}", period));
        Map<String, BigDecimal> result = new HashMap<>();
        if (orders.isEmpty()) {
            log.warn("[分摊] 周期 {} 无订单数据", period);
            return result;
        }
        BigDecimal costPool = totalCost == null ? BigDecimal.ZERO : totalCost;
        for (int i = 0; i < orders.size(); i++) {
            BigDecimal cost = strategy.allocate(costPool, orders, i);
            result.put(orders.get(i).getOrderNo(), cost);
        }
        log.info("[分摊] period={} strategy={} totalCost={} 订单数={}", period, strategyType, costPool, orders.size());
        return result;
    }
}
