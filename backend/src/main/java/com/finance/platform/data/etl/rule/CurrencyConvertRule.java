package com.finance.platform.data.etl.rule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.platform.common.constant.BusinessConstants;
import com.finance.platform.data.entity.ExchangeRateSnapshot;
import com.finance.platform.data.entity.RawOrder;
import com.finance.platform.data.service.ExchangeRateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 币种换算规则：非 CNY 统一折算为人民币存入 settleAmount。
 * <p>
 * 按订单日期查询对应日期的汇率快照，确保不同日期的订单使用正确的汇率。
 * 若订单日期当天没有汇率，取该日期之前最近的汇率；若之前也没有，取之后最近的。
 * 若该币种完全没有任何汇率记录，返回清洗失败。
 * <p>
 * 注意：不再覆盖原始 settleTime，原始结算时间保留。
 */
@Component
@RequiredArgsConstructor
public class CurrencyConvertRule implements CleanRule {

    private final ExchangeRateService exchangeRateService;

    @Override
    public String ruleName() {
        return "currencyConvertRule";
    }

    @Override
    public CleanResult apply(RawOrder order, CleanContext context) {
        try {
            BigDecimal amount = order.getAmount();
            String currency = order.getCurrency();
            if (amount == null) {
                return CleanResult.fail("金额为空，无法换算");
            }
            if (BusinessConstants.CURRENCY_CNY.equals(currency)) {
                order.setSettleAmount(amount);
                return CleanResult.pass();
            }

            // 按订单日期查汇率
            LocalDate rateDate = extractRateDate(order);
            BigDecimal rate = findRateByDate(currency, BusinessConstants.CURRENCY_CNY, rateDate);
            if (rate == null) {
                return CleanResult.fail("缺少币种 " + currency + "->CNY 在 " + rateDate
                        + " 及附近的汇率，请先在「历史汇率导入」页导入对应日期的汇率");
            }
            // 全精度存储：BigDecimal 本身就是高精度类，不人为截断精度。
            // 数据库 DECIMAL(18,4) 支持 4 位小数，足够承载汇率折算结果。
            // 对账时也用全精度计算，避免 setScale 截断产生的虚假差值；
            // 仅在前端展示时按 2 位小数格式化。
            order.setSettleAmount(amount.multiply(rate));
            context.addAction(ruleName(), "订单 " + order.getOrderNo() + " " + currency + " " + amount
                    + " 按汇率 " + rate + " 折算为 CNY " + order.getSettleAmount());
            return CleanResult.pass();
        } catch (Exception e) {
            return CleanResult.fail("币种换算失败：" + e.getMessage());
        }
    }

    /** 提取订单的汇率查询日期：优先用订单时间，无则用结算时间，都无则用当天 */
    private LocalDate extractRateDate(RawOrder order) {
        LocalDateTime t = order.getOrderTime();
        if (t == null) t = order.getSettleTime();
        return t != null ? t.toLocalDate() : LocalDate.now();
    }

    /**
     * 按日期查找汇率：优先精确匹配，无则取 date 之前最近的，再无则取之后最近的。
     */
    private BigDecimal findRateByDate(String from, String to, LocalDate date) {
        // 1. 精确匹配当天
        ExchangeRateSnapshot exact = exchangeRateService.getOne(new LambdaQueryWrapper<ExchangeRateSnapshot>()
                .eq(ExchangeRateSnapshot::getFromCurrency, from)
                .eq(ExchangeRateSnapshot::getToCurrency, to)
                .eq(ExchangeRateSnapshot::getRateDate, date)
                .last("LIMIT 1"));
        if (exact != null) return exact.getRate();

        // 2. 取 date 之前最近的
        ExchangeRateSnapshot before = exchangeRateService.getOne(new LambdaQueryWrapper<ExchangeRateSnapshot>()
                .eq(ExchangeRateSnapshot::getFromCurrency, from)
                .eq(ExchangeRateSnapshot::getToCurrency, to)
                .lt(ExchangeRateSnapshot::getRateDate, date)
                .orderByDesc(ExchangeRateSnapshot::getRateDate)
                .last("LIMIT 1"));
        if (before != null) return before.getRate();

        // 3. 取 date 之后最近的
        ExchangeRateSnapshot after = exchangeRateService.getOne(new LambdaQueryWrapper<ExchangeRateSnapshot>()
                .eq(ExchangeRateSnapshot::getFromCurrency, from)
                .eq(ExchangeRateSnapshot::getToCurrency, to)
                .gt(ExchangeRateSnapshot::getRateDate, date)
                .orderByAsc(ExchangeRateSnapshot::getRateDate)
                .last("LIMIT 1"));
        return after != null ? after.getRate() : null;
    }
}
