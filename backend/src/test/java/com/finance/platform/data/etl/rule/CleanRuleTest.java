package com.finance.platform.data.etl.rule;

import com.finance.platform.data.entity.ExchangeRateSnapshot;
import com.finance.platform.data.entity.RawOrder;
import com.finance.platform.data.service.ExchangeRateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 清洗规则引擎单元测试（方案 B 核心）
 * <p>
 * 覆盖 5 个内置规则 + 责任链执行器：
 * 1. TrimRule：字段标准化（trim、币种大写、平台归一）
 * 2. DefaultCurrencyRule：缺省币种补 CNY
 * 3. FilterInvalidRule：异常数据过滤
 * 4. CurrencyConvertRule：币种换算为 CNY 写入 settleAmount
 * 5. DeduplicateRule：复合键去重
 * 6. CleanRuleChain：责任链构建与 applyAll 短路逻辑
 */
@DisplayName("清洗规则引擎测试")
class CleanRuleTest {

    // ==================== 1. TrimRule ====================

    @Test
    @DisplayName("TrimRule：trim 空白、币种大写、平台名归一")
    void trimRuleStandardizesFields() {
        RawOrder o = new RawOrder();
        o.setOrderNo("  ORD-001  ");
        o.setPlatform("amazon");
        o.setShopId("  SHOP-A  ");
        o.setCurrency("  usd ");

        TrimRule rule = new TrimRule();
        CleanRule.CleanResult result = rule.apply(o, new CleanContext("B1"));

        assertThat(result.ok()).isTrue();
        assertThat(o.getOrderNo()).isEqualTo("ORD-001");
        assertThat(o.getPlatform()).isEqualTo("Amazon");
        assertThat(o.getShopId()).isEqualTo("SHOP-A");
        assertThat(o.getCurrency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("TrimRule：null 字段不抛异常")
    void trimRuleHandlesNull() {
        RawOrder o = new RawOrder();
        TrimRule rule = new TrimRule();
        CleanRule.CleanResult result = rule.apply(o, new CleanContext("B1"));
        assertThat(result.ok()).isTrue();
    }

    @Test
    @DisplayName("TrimRule：ruleName 为 trimRule")
    void trimRuleName() {
        assertThat(new TrimRule().ruleName()).isEqualTo("trimRule");
    }

    // ==================== 2. DefaultCurrencyRule ====================

    @Test
    @DisplayName("DefaultCurrencyRule：币种为空补 CNY")
    void defaultCurrencyRuleFillsCny() {
        RawOrder o = new RawOrder();
        o.setCurrency(null);
        CleanRule.CleanResult result = new DefaultCurrencyRule().apply(o, new CleanContext("B1"));
        assertThat(result.ok()).isTrue();
        assertThat(o.getCurrency()).isEqualTo("CNY");
    }

    @Test
    @DisplayName("DefaultCurrencyRule：币种为空白补 CNY")
    void defaultCurrencyRuleFillsCnyWhenBlank() {
        RawOrder o = new RawOrder();
        o.setCurrency("   ");
        CleanRule.CleanResult result = new DefaultCurrencyRule().apply(o, new CleanContext("B1"));
        assertThat(result.ok()).isTrue();
        assertThat(o.getCurrency()).isEqualTo("CNY");
    }

    @Test
    @DisplayName("DefaultCurrencyRule：已有币种不覆盖")
    void defaultCurrencyRuleKeepsExisting() {
        RawOrder o = new RawOrder();
        o.setCurrency("USD");
        CleanRule.CleanResult result = new DefaultCurrencyRule().apply(o, new CleanContext("B1"));
        assertThat(result.ok()).isTrue();
        assertThat(o.getCurrency()).isEqualTo("USD");
    }

    // ==================== 3. FilterInvalidRule ====================

    @Test
    @DisplayName("FilterInvalidRule：订单号为空失败")
    void filterInvalidRuleFailsOnEmptyOrderNo() {
        RawOrder o = new RawOrder();
        o.setOrderNo("");
        o.setAmount(new BigDecimal("100"));
        CleanRule.CleanResult result = new FilterInvalidRule().apply(o, new CleanContext("B1"));
        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).contains("订单号为空");
    }

    @Test
    @DisplayName("FilterInvalidRule：金额为空失败")
    void filterInvalidRuleFailsOnNullAmount() {
        RawOrder o = new RawOrder();
        o.setOrderNo("ORD-001");
        o.setAmount(null);
        CleanRule.CleanResult result = new FilterInvalidRule().apply(o, new CleanContext("B1"));
        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).contains("金额为空");
    }

    @Test
    @DisplayName("FilterInvalidRule：金额非正数失败")
    void filterInvalidRuleFailsOnNonPositiveAmount() {
        RawOrder o = new RawOrder();
        o.setOrderNo("ORD-001");
        o.setAmount(new BigDecimal("0"));
        CleanRule.CleanResult result = new FilterInvalidRule().apply(o, new CleanContext("B1"));
        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).contains("非正数");
    }

    @Test
    @DisplayName("FilterInvalidRule：下单晚于结算失败")
    void filterInvalidRuleFailsOnTimeInversion() {
        RawOrder o = new RawOrder();
        o.setOrderNo("ORD-001");
        o.setAmount(new BigDecimal("100"));
        o.setOrderTime(LocalDateTime.of(2026, 7, 10, 12, 0));
        o.setSettleTime(LocalDateTime.of(2026, 7, 9, 12, 0));
        CleanRule.CleanResult result = new FilterInvalidRule().apply(o, new CleanContext("B1"));
        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).contains("下单时间晚于结算时间");
    }

    @Test
    @DisplayName("FilterInvalidRule：正常数据通过")
    void filterInvalidRulePassesValid() {
        RawOrder o = new RawOrder();
        o.setOrderNo("ORD-001");
        o.setAmount(new BigDecimal("100"));
        o.setOrderTime(LocalDateTime.of(2026, 7, 1, 12, 0));
        o.setSettleTime(LocalDateTime.of(2026, 7, 5, 12, 0));
        CleanRule.CleanResult result = new FilterInvalidRule().apply(o, new CleanContext("B1"));
        assertThat(result.ok()).isTrue();
    }

    // ==================== 4. CurrencyConvertRule ====================

    @Test
    @DisplayName("CurrencyConvertRule：CNY 直接写入 settleAmount")
    void currencyConvertRuleCny() {
        RawOrder o = new RawOrder();
        o.setAmount(new BigDecimal("1000"));
        o.setCurrency("CNY");
        ExchangeRateService svc = mock(ExchangeRateService.class);
        CleanRule.CleanResult result = new CurrencyConvertRule(svc).apply(o, new CleanContext("B1"));
        assertThat(result.ok()).isTrue();
        assertThat(o.getSettleAmount()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("CurrencyConvertRule：USD 换算为 CNY")
    void currencyConvertRuleUsdToCny() {
        RawOrder o = new RawOrder();
        o.setAmount(new BigDecimal("1000"));
        o.setCurrency("USD");
        o.setOrderTime(LocalDateTime.of(2025, 1, 15, 10, 0));
        ExchangeRateService svc = mock(ExchangeRateService.class);
        ExchangeRateSnapshot snap = new ExchangeRateSnapshot();
        snap.setRate(new BigDecimal("7.25"));
        when(svc.getOne(any())).thenReturn(snap);
        CleanRule.CleanResult result = new CurrencyConvertRule(svc).apply(o, new CleanContext("B1"));
        assertThat(result.ok()).isTrue();
        assertThat(o.getSettleAmount()).isEqualByComparingTo("7250.000000");
    }

    @Test
    @DisplayName("CurrencyConvertRule：金额为空失败")
    void currencyConvertRuleNullAmount() {
        RawOrder o = new RawOrder();
        o.setAmount(null);
        o.setCurrency("USD");
        ExchangeRateService svc = mock(ExchangeRateService.class);
        CleanRule.CleanResult result = new CurrencyConvertRule(svc).apply(o, new CleanContext("B1"));
        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).contains("金额为空");
    }

    @Test
    @DisplayName("CurrencyConvertRule：缺少汇率返回失败")
    void currencyConvertRuleException() {
        RawOrder o = new RawOrder();
        o.setAmount(new BigDecimal("1000"));
        o.setCurrency("XXX");
        o.setOrderTime(LocalDateTime.of(2025, 1, 15, 10, 0));
        ExchangeRateService svc = mock(ExchangeRateService.class);
        when(svc.getOne(any())).thenReturn(null);
        CleanRule.CleanResult result = new CurrencyConvertRule(svc).apply(o, new CleanContext("B1"));
        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).contains("缺少币种");
    }

    // ==================== 5. DeduplicateRule ====================

    @Test
    @DisplayName("DeduplicateRule：首次出现通过")
    void deduplicateRuleFirstOccurrence() {
        RawOrder o = new RawOrder();
        o.setPlatform("Amazon");
        o.setOrderNo("ORD-001");
        CleanContext ctx = new CleanContext("B1");
        CleanRule.CleanResult result = new DeduplicateRule().apply(o, ctx);
        assertThat(result.ok()).isTrue();
        assertThat(ctx.getSeenOrderKeys()).contains("Amazon|ORD-001");
    }

    @Test
    @DisplayName("DeduplicateRule：重复订单号失败")
    void deduplicateRuleDuplicate() {
        RawOrder o = new RawOrder();
        o.setPlatform("Amazon");
        o.setOrderNo("ORD-001");
        CleanContext ctx = new CleanContext("B1");
        ctx.getSeenOrderKeys().add("Amazon|ORD-001");
        CleanRule.CleanResult result = new DeduplicateRule().apply(o, ctx);
        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).contains("重复订单号");
    }

    @Test
    @DisplayName("DeduplicateRule：同订单号不同平台通过")
    void deduplicateRuleSameOrderNoDifferentPlatform() {
        RawOrder o = new RawOrder();
        o.setPlatform("Shopee");
        o.setOrderNo("ORD-001");
        CleanContext ctx = new CleanContext("B1");
        ctx.getSeenOrderKeys().add("Amazon|ORD-001");
        CleanRule.CleanResult result = new DeduplicateRule().apply(o, ctx);
        assertThat(result.ok()).isTrue();
    }

    // ==================== 6. CleanRuleChain ====================

    @Test
    @DisplayName("CleanRuleChain：buildChain 按名称构建有序规则列表")
    void buildChainSelectsRulesByName() {
        List<CleanRule> rules = List.of(
                new TrimRule(),
                new DefaultCurrencyRule(),
                new FilterInvalidRule(),
                new DeduplicateRule());
        CleanRuleChain chain = new CleanRuleChain(rules);
        List<CleanRule> built = chain.buildChain("trimRule, filterInvalidRule, deduplicateRule");
        assertThat(built).hasSize(3);
        assertThat(built.get(0)).isInstanceOf(TrimRule.class);
        assertThat(built.get(1)).isInstanceOf(FilterInvalidRule.class);
        assertThat(built.get(2)).isInstanceOf(DeduplicateRule.class);
    }

    @Test
    @DisplayName("CleanRuleChain：未知规则名跳过")
    void buildChainSkipsUnknownRule() {
        List<CleanRule> rules = List.of(new TrimRule(), new FilterInvalidRule());
        CleanRuleChain chain = new CleanRuleChain(rules);
        List<CleanRule> built = chain.buildChain("trimRule, unknownRule, filterInvalidRule");
        assertThat(built).hasSize(2);
    }

    @Test
    @DisplayName("CleanRuleChain：空规则名返回空列表")
    void buildChainEmpty() {
        CleanRuleChain chain = new CleanRuleChain(List.of(new TrimRule()));
        assertThat(chain.buildChain(null)).isEmpty();
        assertThat(chain.buildChain("")).isEmpty();
        assertThat(chain.buildChain("   ")).isEmpty();
    }

    @Test
    @DisplayName("CleanRuleChain：applyAll 全部通过返回 ok")
    void applyAllAllPass() {
        List<CleanRule> rules = List.of(new TrimRule(), new FilterInvalidRule());
        CleanRuleChain chain = new CleanRuleChain(rules);
        RawOrder o = new RawOrder();
        o.setOrderNo("ORD-001");
        o.setAmount(new BigDecimal("100"));
        CleanRule.CleanResult result = chain.applyAll(rules, o, new CleanContext("B1"));
        assertThat(result.ok()).isTrue();
    }

    @Test
    @DisplayName("CleanRuleChain：applyAll 首个失败短路返回")
    void applyAllShortCircuitOnFirstFail() {
        TrimRule trimRule = new TrimRule();
        FilterInvalidRule filterRule = new FilterInvalidRule();
        List<CleanRule> rules = List.of(trimRule, filterRule);
        CleanRuleChain chain = new CleanRuleChain(rules);
        RawOrder o = new RawOrder();
        o.setOrderNo(null);
        o.setAmount(new BigDecimal("100"));
        CleanRule.CleanResult result = chain.applyAll(rules, o, new CleanContext("B1"));
        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).contains("订单号为空");
    }

    @Test
    @DisplayName("CleanRuleChain：完整清洗流程集成测试")
    void fullChainIntegration() {
        // 构建完整规则链：trim → defaultCurrency → filterInvalid → currencyConvert → deduplicate
        ExchangeRateService svc = mock(ExchangeRateService.class);
        ExchangeRateSnapshot snap = new ExchangeRateSnapshot();
        snap.setRate(new BigDecimal("7.25"));
        when(svc.getOne(any())).thenReturn(snap);
        List<CleanRule> rules = List.of(
                new TrimRule(),
                new DefaultCurrencyRule(),
                new FilterInvalidRule(),
                new CurrencyConvertRule(svc),
                new DeduplicateRule());
        CleanRuleChain chain = new CleanRuleChain(rules);
        List<CleanRule> built = chain.buildChain(
                "trimRule, defaultCurrencyRule, filterInvalidRule, currencyConvertRule, deduplicateRule");

        RawOrder o = new RawOrder();
        o.setOrderNo("  ORD-001  ");
        o.setPlatform("amazon");
        o.setCurrency("usd");
        o.setAmount(new BigDecimal("1000"));
        o.setOrderTime(LocalDateTime.of(2025, 1, 15, 10, 0));

        CleanRule.CleanResult result = chain.applyAll(built, o, new CleanContext("B1"));
        assertThat(result.ok()).isTrue();
        assertThat(o.getOrderNo()).isEqualTo("ORD-001");
        assertThat(o.getPlatform()).isEqualTo("Amazon");
        assertThat(o.getCurrency()).isEqualTo("USD");
        assertThat(o.getSettleAmount()).isEqualByComparingTo("7250");
    }
}
