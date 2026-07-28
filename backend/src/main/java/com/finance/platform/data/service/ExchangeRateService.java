package com.finance.platform.data.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.finance.platform.data.entity.ExchangeRateSnapshot;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 汇率服务接口
 * <p>
 * 提供汇率查询、快照保存及内存缓存刷新能力。
 */
public interface ExchangeRateService extends IService<ExchangeRateSnapshot> {

    /**
     * 获取最新汇率（先查缓存，未命中查库）
     *
     * @param from 源币种
     * @param to   目标币种
     * @return 汇率
     */
    BigDecimal getLatestRate(String from, String to);

    /**
     * 保存汇率快照
     *
     * @param snapshot 汇率快照
     */
    void saveSnapshot(ExchangeRateSnapshot snapshot);

    /**
     * 刷新内存汇率缓存
     */
    void refreshCache();

    /**
     * 自动补全缺失日期的汇率：用缺失日期之前最近一个交易日的汇率填充
     * <p>
     * 策略：对每个缺失日期，向前查找最近的已存在汇率快照，用其 rate 创建新的快照。
     * 若缺失日期之前没有任何汇率记录，则尝试向后查找；若仍无，跳过该日期并记入 failedDates。
     *
     * @param fromCurrency 源币种
     * @param toCurrency   目标币种
     * @param missingDates 需要补全的日期列表
     * @return 实际补全的日期列表（成功入库的）
     */
    List<LocalDate> autoFillMissingRates(String fromCurrency, String toCurrency, List<LocalDate> missingDates);
}
