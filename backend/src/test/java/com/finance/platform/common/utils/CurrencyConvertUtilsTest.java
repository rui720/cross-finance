package com.finance.platform.common.utils;

import com.finance.platform.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 币种换算工具单元测试
 * <p>
 * 验证：
 * 1. CNY 原值返回
 * 2. 外币按汇率折算 CNY
 * 3. 缺失汇率抛 BusinessException
 * 4. 跨币种经 CNY 中转换算
 */
@DisplayName("币种换算工具测试")
class CurrencyConvertUtilsTest {

    private CurrencyConvertUtils utils;

    @BeforeEach
    void setUp() {
        utils = new CurrencyConvertUtils();
        // 注入测试汇率：1 USD = 7.25 CNY, 1 EUR = 7.85 CNY
        utils.updateRate("USD", new BigDecimal("7.25"));
        utils.updateRate("EUR", new BigDecimal("7.85"));
    }

    @Test
    @DisplayName("CNY 原值返回")
    void cnyReturnsOriginal() {
        BigDecimal result = utils.toCny(new BigDecimal("100.00"), "CNY");
        assertThat(result).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    @DisplayName("USD 按汇率折算 CNY")
    void usdConvertsToCny() {
        BigDecimal result = utils.toCny(new BigDecimal("100"), "USD");
        // 100 * 7.25 = 725.000000
        assertThat(result).isEqualByComparingTo(new BigDecimal("725.00"));
    }

    @Test
    @DisplayName("缺失汇率抛 BusinessException")
    void missingRateThrowsException() {
        assertThatThrownBy(() -> utils.toCny(new BigDecimal("100"), "JPY"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("缺少币种汇率：JPY");
    }

    @Test
    @DisplayName("跨币种经 CNY 中转：USD -> EUR")
    void crossCurrencyConvert() {
        // 100 USD = 725 CNY, 725 / 7.85 = 92.356688
        BigDecimal result = utils.convert(new BigDecimal("100"), "USD", "EUR");
        assertThat(result).isEqualByComparingTo(new BigDecimal("92.356688"));
    }

    @Test
    @DisplayName("同币种 convert 原值返回")
    void sameCurrencyConvertReturnsOriginal() {
        BigDecimal result = utils.convert(new BigDecimal("100"), "CNY", "CNY");
        assertThat(result).isEqualByComparingTo(new BigDecimal("100"));
    }

    @Test
    @DisplayName("convert 缺失目标币种汇率抛异常")
    void convertMissingToRateThrows() {
        assertThatThrownBy(() -> utils.convert(new BigDecimal("100"), "USD", "JPY"))
                .isInstanceOf(BusinessException.class);
    }
}
