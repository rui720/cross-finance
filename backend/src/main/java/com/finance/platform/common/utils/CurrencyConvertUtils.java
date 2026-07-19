package com.finance.platform.common.utils;

import com.finance.platform.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 汇率换算工具
 * <p>
 * 维护一份内存汇率表（由 ExchangeRateService 定时刷新），
 * 统一以 CNY 为基准币种，跨币种换算走 CNY 中转。
 */
@Component
public class CurrencyConvertUtils {

    /** 汇率表：1 单位外币 = 多少 CNY */
    private final Map<String, BigDecimal> rateToCny = new ConcurrentHashMap<>();

    /**
     * 更新汇率快照（由定时任务或汇率服务调用）
     */
    public void updateRate(String currency, BigDecimal rate) {
        rateToCny.put(currency, rate);
    }

    /**
     * 换算金额
     *
     * @param amount 原始金额
     * @param fromCurrency 原始币种
     * @param toCurrency 目标币种
     */
    public BigDecimal convert(BigDecimal amount, String fromCurrency, String toCurrency) {
        if (fromCurrency.equals(toCurrency)) {
            return amount;
        }
        BigDecimal fromRate = rateToCny.get(fromCurrency);
        BigDecimal toRate = rateToCny.get(toCurrency);
        if (fromRate == null || toRate == null) {
            throw new BusinessException("缺少币种汇率：" + fromCurrency + " -> " + toCurrency);
        }
        // 先折算为 CNY，再折算为目标币种
        BigDecimal cnyAmount = amount.multiply(fromRate);
        return cnyAmount.divide(toRate, 6, RoundingMode.HALF_UP);
    }

    /**
     * 统一换算为人民币
     */
    public BigDecimal toCny(BigDecimal amount, String fromCurrency) {
        if ("CNY".equals(fromCurrency)) {
            return amount;
        }
        BigDecimal rate = rateToCny.get(fromCurrency);
        if (rate == null) {
            throw new BusinessException("缺少币种汇率：" + fromCurrency);
        }
        return amount.multiply(rate).setScale(6, RoundingMode.HALF_UP);
    }
}
