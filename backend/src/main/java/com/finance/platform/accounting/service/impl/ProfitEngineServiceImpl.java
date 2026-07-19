package com.finance.platform.accounting.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.finance.platform.accounting.entity.ProfitReport;
import com.finance.platform.accounting.mapper.ProfitReportMapper;
import com.finance.platform.accounting.service.CostAllocationService;
import com.finance.platform.accounting.service.ProfitEngineService;
import com.finance.platform.common.exception.BusinessException;
import com.finance.platform.common.utils.CurrencyConvertUtils;
import com.finance.platform.data.entity.RawOrder;
import com.finance.platform.data.mapper.RawOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 利润计算引擎实现
 * <p>
 * 取出周期内 raw_order 数据，将平台费汇总为成本池并按金额占比分摊，
 * 逐单计算：人民币金额 = 原始金额折算 CNY；利润 = 人民币金额 - 分摊成本；
 * 利润率 = 利润 / 人民币金额，最后批量写入 profit_report。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfitEngineServiceImpl extends ServiceImpl<ProfitReportMapper, ProfitReport> implements ProfitEngineService {

    /** 默认采用按金额分摊策略 */
    private static final String DEFAULT_STRATEGY = "AMOUNT";

    private final ProfitReportMapper profitReportMapper;
    private final CostAllocationService costAllocationService;
    private final RawOrderMapper rawOrderMapper;
    private final CurrencyConvertUtils currencyConvertUtils;

    @Override
    public void calculate(String period) {
        if (StrUtil.isBlank(period)) {
            throw new BusinessException("核算周期不能为空");
        }
        log.info("[核算] 开始利润核算 period={}", period);
        // 查出周期内 raw_order 数据（按下单时间的年月匹配周期）
        List<RawOrder> orders = rawOrderMapper.selectList(new LambdaQueryWrapper<RawOrder>()
                .apply("DATE_FORMAT(order_time, '%Y%m') = {0}", period));
        if (orders.isEmpty()) {
            log.warn("[核算] 周期 {} 无订单数据", period);
            return;
        }
        // 幂等：先清除该周期旧报表，避免重复核算产生脏数据
        profitReportMapper.delete(new LambdaQueryWrapper<ProfitReport>()
                .eq(ProfitReport::getPeriod, period));
        // 成本池：取周期内全部平台费之和
        BigDecimal totalCost = BigDecimal.ZERO;
        for (RawOrder o : orders) {
            totalCost = totalCost.add(o.getFee() == null ? BigDecimal.ZERO : o.getFee());
        }
        // 按金额占比分摊成本到各订单
        Map<String, BigDecimal> costMap = costAllocationService.allocate(period, totalCost, DEFAULT_STRATEGY);

        List<ProfitReport> reports = new ArrayList<>(orders.size());
        for (RawOrder order : orders) {
            BigDecimal originalAmount = order.getAmount() == null ? BigDecimal.ZERO : order.getAmount();
            BigDecimal cnyAmount = currencyConvertUtils.toCny(originalAmount, order.getCurrency());
            BigDecimal costAmount = costMap.getOrDefault(order.getOrderNo(), BigDecimal.ZERO);
            BigDecimal profit = cnyAmount.subtract(costAmount);
            BigDecimal profitRate = cnyAmount.compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO
                    : profit.divide(cnyAmount, 6, RoundingMode.HALF_UP);

            ProfitReport report = new ProfitReport();
            report.setPeriod(period);
            report.setOrderNo(order.getOrderNo());
            report.setPlatform(order.getPlatform());
            report.setCurrency(order.getCurrency());
            report.setOriginalAmount(originalAmount);
            report.setCnyAmount(cnyAmount);
            report.setCostAmount(costAmount);
            report.setProfitAmount(profit);
            report.setProfitRate(profitRate);
            reports.add(report);
        }
        // 批量插入 profit_report
        this.saveBatch(reports);
        log.info("[核算] 周期 {} 利润核算完成，共生成 {} 条报表", period, reports.size());
    }

    @Override
    public Page<ProfitReport> getReport(String period, int page, int size) {
        Page<ProfitReport> pageObj = new Page<>(page, size);
        return profitReportMapper.selectPage(pageObj, new LambdaQueryWrapper<ProfitReport>()
                .eq(StrUtil.isNotBlank(period), ProfitReport::getPeriod, period)
                .orderByDesc(ProfitReport::getId));
    }
}
