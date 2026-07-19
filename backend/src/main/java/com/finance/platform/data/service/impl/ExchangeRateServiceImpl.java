package com.finance.platform.data.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.finance.platform.common.constant.BusinessConstants;
import com.finance.platform.common.exception.BusinessException;
import com.finance.platform.common.utils.CurrencyConvertUtils;
import com.finance.platform.data.entity.ExchangeRateSnapshot;
import com.finance.platform.data.mapper.ExchangeRateSnapshotMapper;
import com.finance.platform.data.service.ExchangeRateService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 汇率快照服务实现
 * <p>
 * 汇率查询走库（缓存由 {@link CurrencyConvertUtils} 内存表维护），
 * 定时任务调用 {@link #refreshCache()} 将最新汇率加载到内存换算工具。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeRateServiceImpl extends ServiceImpl<ExchangeRateSnapshotMapper, ExchangeRateSnapshot> implements ExchangeRateService {

    private final CurrencyConvertUtils currencyConvertUtils;

    /**
     * 启动时自动加载最新汇率到内存换算表，避免利润核算时缺少汇率
     */
    @PostConstruct
    public void init() {
        try {
            refreshCache();
        } catch (Exception e) {
            log.warn("[ExchangeRate] 启动加载汇率失败，将在首次调用时重试：{}", e.getMessage());
        }
    }

    @Override
    public BigDecimal getLatestRate(String from, String to) {
        if (from.equals(to)) {
            return BigDecimal.ONE;
        }
        // 先查当日，未命中再取历史最新一条
        ExchangeRateSnapshot snapshot = getOne(new LambdaQueryWrapper<ExchangeRateSnapshot>()
                .eq(ExchangeRateSnapshot::getFromCurrency, from)
                .eq(ExchangeRateSnapshot::getToCurrency, to)
                .eq(ExchangeRateSnapshot::getRateDate, LocalDate.now())
                .last("LIMIT 1"));
        if (snapshot == null) {
            snapshot = getOne(new LambdaQueryWrapper<ExchangeRateSnapshot>()
                    .eq(ExchangeRateSnapshot::getFromCurrency, from)
                    .eq(ExchangeRateSnapshot::getToCurrency, to)
                    .orderByDesc(ExchangeRateSnapshot::getRateDate)
                    .last("LIMIT 1"));
        }
        if (snapshot == null) {
            throw new BusinessException("未找到汇率：" + from + " -> " + to);
        }
        return snapshot.getRate();
    }

    @Override
    public void saveSnapshot(ExchangeRateSnapshot snapshot) {
        save(snapshot);
        // 以 CNY 为目标的汇率同步刷新到内存换算表
        if (BusinessConstants.CURRENCY_CNY.equals(snapshot.getToCurrency())) {
            currencyConvertUtils.updateRate(snapshot.getFromCurrency(), snapshot.getRate());
        }
        log.info("[ExchangeRate] 保存汇率快照 {} -> {} = {}", snapshot.getFromCurrency(), snapshot.getToCurrency(), snapshot.getRate());
    }

    @Override
    public void refreshCache() {
        // 优先加载当日汇率，无则取历史最新一批
        List<ExchangeRateSnapshot> list = list(new LambdaQueryWrapper<ExchangeRateSnapshot>()
                .eq(ExchangeRateSnapshot::getRateDate, LocalDate.now()));
        if (list.isEmpty()) {
            ExchangeRateSnapshot latest = getOne(new LambdaQueryWrapper<ExchangeRateSnapshot>()
                    .orderByDesc(ExchangeRateSnapshot::getRateDate)
                    .last("LIMIT 1"));
            if (latest != null) {
                list = list(new LambdaQueryWrapper<ExchangeRateSnapshot>()
                        .eq(ExchangeRateSnapshot::getRateDate, latest.getRateDate()));
            }
        }
        for (ExchangeRateSnapshot snapshot : list) {
            // 内存换算表以 CNY 为基准：1 单位外币 = rate CNY
            if (BusinessConstants.CURRENCY_CNY.equals(snapshot.getToCurrency())) {
                currencyConvertUtils.updateRate(snapshot.getFromCurrency(), snapshot.getRate());
            }
        }
        log.info("[ExchangeRate] 内存汇率表已刷新，共 {} 条", list.size());
    }
}
