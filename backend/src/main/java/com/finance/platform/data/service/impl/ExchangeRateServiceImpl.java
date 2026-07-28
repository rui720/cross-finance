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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    @Override
    public List<LocalDate> autoFillMissingRates(String fromCurrency, String toCurrency, List<LocalDate> missingDates) {
        if (missingDates == null || missingDates.isEmpty()) {
            return List.of();
        }
        log.info("[ExchangeRate] 自动补全 {}->{}, 缺失 {} 天", fromCurrency, toCurrency, missingDates.size());
        // 预查该币种对所有的已有汇率快照，按日期排序
        List<ExchangeRateSnapshot> existing = list(new LambdaQueryWrapper<ExchangeRateSnapshot>()
                .eq(ExchangeRateSnapshot::getFromCurrency, fromCurrency)
                .eq(ExchangeRateSnapshot::getToCurrency, toCurrency)
                .orderByAsc(ExchangeRateSnapshot::getRateDate));
        if (existing.isEmpty()) {
            log.warn("[ExchangeRate] 币种 {}->{} 无任何历史汇率，无法自动补全", fromCurrency, toCurrency);
            return List.of();
        }
        // 建立日期 -> rate 索引
        java.util.Map<LocalDate, BigDecimal> existingMap = new java.util.HashMap<>();
        for (ExchangeRateSnapshot s : existing) {
            existingMap.put(s.getRateDate(), s.getRate());
        }
        // 按日期排序的已有日期列表（用于二分查找最近交易日）
        List<LocalDate> sortedExistingDates = new ArrayList<>(existingMap.keySet());
        java.util.Collections.sort(sortedExistingDates);

        List<ExchangeRateSnapshot> toSave = new ArrayList<>();
        List<LocalDate> filledDates = new ArrayList<>();
        Set<LocalDate> missingSet = new HashSet<>(missingDates);

        for (LocalDate d : missingDates) {
            // 跳过已存在的日期
            if (existingMap.containsKey(d)) continue;
            // 查找最近的已有汇率：优先向前，其次向后
            BigDecimal rate = findNearestRate(d, sortedExistingDates, existingMap);
            if (rate == null) {
                log.warn("[ExchangeRate] 日期 {} 找不到任何邻近汇率，跳过", d);
                continue;
            }
            ExchangeRateSnapshot snapshot = new ExchangeRateSnapshot();
            snapshot.setRateDate(d);
            snapshot.setFromCurrency(fromCurrency);
            snapshot.setToCurrency(toCurrency);
            snapshot.setRate(rate);
            snapshot.setSource("AUTO_FILL");
            toSave.add(snapshot);
            filledDates.add(d);
        }
        if (!toSave.isEmpty()) {
            saveBatch(toSave);
            // 刷新内存缓存
            if (BusinessConstants.CURRENCY_CNY.equals(toCurrency)) {
                for (ExchangeRateSnapshot s : toSave) {
                    currencyConvertUtils.updateRate(s.getFromCurrency(), s.getRate());
                }
            }
            log.info("[ExchangeRate] 自动补全完成，{}->{} 共填充 {} 天", fromCurrency, toCurrency, toSave.size());
        }
        return filledDates;
    }

    /**
     * 在已排序的日期列表中查找目标日期 d 的最近汇率：
     * 1. 优先取 d 之前最近的日期（前一日汇率）
     * 2. 若无，取 d 之后最近的日期
     */
    private BigDecimal findNearestRate(LocalDate d, List<LocalDate> sortedDates,
                                        java.util.Map<LocalDate, BigDecimal> rateMap) {
        // 二分查找插入位置
        int idx = java.util.Collections.binarySearch(sortedDates, d);
        if (idx >= 0) {
            // 精确命中（不应该发生，调用方已过滤）
            return rateMap.get(sortedDates.get(idx));
        }
        int insertPos = -(idx + 1);
        // 优先向前找
        if (insertPos > 0) {
            LocalDate prevDate = sortedDates.get(insertPos - 1);
            return rateMap.get(prevDate);
        }
        // 否则向后找
        if (insertPos < sortedDates.size()) {
            LocalDate nextDate = sortedDates.get(insertPos);
            return rateMap.get(nextDate);
        }
        return null;
    }
}
