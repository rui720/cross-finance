package com.finance.platform.accounting.service.impl;

import com.finance.platform.accounting.entity.ProfitReport;
import com.finance.platform.accounting.mapper.ProfitReportMapper;
import com.finance.platform.accounting.service.CostAllocationService;
import com.finance.platform.common.exception.BusinessException;
import com.finance.platform.common.utils.CurrencyConvertUtils;
import com.finance.platform.data.entity.RawOrder;
import com.finance.platform.data.mapper.RawOrderMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 利润核算引擎单元测试
 * <p>
 * 验证：
 * 1. 周期为空抛异常
 * 2. 无订单数据时安全返回
 * 3. 幂等性：核算前先删除旧报表
 * 4. 利润计算正确性（CNY 折算、成本分摊、利润率）
 * 5. 批量插入报表
 * <p>
 * 注意：ProfitEngineServiceImpl 继承 ServiceImpl，saveBatch 依赖 SqlHelper，
 * 单元测试中通过匿名子类重写 saveBatch 来绕过真实数据库调用。
 */
@DisplayName("利润核算引擎测试")
@ExtendWith(MockitoExtension.class)
class ProfitEngineServiceImplTest {

    @Mock
    private ProfitReportMapper profitReportMapper;

    @Mock
    private CostAllocationService costAllocationService;

    @Mock
    private RawOrderMapper rawOrderMapper;

    @Mock
    private CurrencyConvertUtils currencyConvertUtils;

    /**
     * 创建测试用 Service 实例，重写 saveBatch 避免依赖 SqlSessionFactory
     */
    private ProfitEngineServiceImpl createService(List<ProfitReport> capturedReports) {
        return new ProfitEngineServiceImpl(profitReportMapper, costAllocationService,
                rawOrderMapper, currencyConvertUtils) {
            @Override
            public boolean saveBatch(java.util.Collection<ProfitReport> entityList) {
                if (capturedReports != null) {
                    capturedReports.addAll(entityList);
                }
                return true;
            }
        };
    }

    private RawOrder buildOrder(String orderNo, String platform, String currency,
                                BigDecimal amount, BigDecimal fee) {
        RawOrder o = new RawOrder();
        o.setOrderNo(orderNo);
        o.setPlatform(platform);
        o.setCurrency(currency);
        o.setAmount(amount);
        o.setFee(fee);
        o.setOrderTime(LocalDateTime.of(2026, 7, 1, 10, 0));
        return o;
    }

    // ==================== 异常场景 ====================
    @Test
    @DisplayName("核算周期为空抛异常")
    void calculateBlankPeriodThrows() {
        ProfitEngineServiceImpl service = createService(null);
        assertThatThrownBy(() -> service.calculate(""))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("核算周期不能为空");
        assertThatThrownBy(() -> service.calculate(null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("核算周期不能为空");
        assertThatThrownBy(() -> service.calculate("   "))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("核算周期不能为空");
    }

    @Test
    @DisplayName("周期内无订单数据时安全返回，不生成报表")
    void calculateNoOrdersReturnsSafely() {
        when(rawOrderMapper.selectList(any())).thenReturn(Collections.emptyList());

        ProfitEngineServiceImpl service = createService(null);
        service.calculate("202607");

        // 无数据时不应删除旧报表，也不应插入
        verify(profitReportMapper, never()).delete(any());
        verify(costAllocationService, never()).allocate(anyString(), any(), anyString());
    }

    // ==================== 核心核算逻辑 ====================
    @Test
    @DisplayName("核算正确性：CNY 订单利润 = 金额 - 分摊成本")
    void calculateCnyOrderProfit() {
        List<RawOrder> orders = Collections.singletonList(
                buildOrder("ORD001", "Amazon", "CNY", new BigDecimal("1000"), new BigDecimal("100"))
        );
        when(rawOrderMapper.selectList(any())).thenReturn(orders);

        // 成本分摊：总平台费 100，分摊到 ORD001 = 100
        Map<String, BigDecimal> costMap = new HashMap<>();
        costMap.put("ORD001", new BigDecimal("100.00"));
        when(costAllocationService.allocate(eq("202607"), eq(new BigDecimal("100")), eq("AMOUNT")))
                .thenReturn(costMap);

        // CNY 直接返回原值
        when(currencyConvertUtils.toCny(new BigDecimal("1000"), "CNY"))
                .thenReturn(new BigDecimal("1000"));

        List<ProfitReport> captured = new java.util.ArrayList<>();
        ProfitEngineServiceImpl service = createService(captured);

        service.calculate("202607");

        assertThat(captured).hasSize(1);
        ProfitReport report = captured.get(0);
        assertThat(report.getOrderNo()).isEqualTo("ORD001");
        assertThat(report.getPlatform()).isEqualTo("Amazon");
        assertThat(report.getCurrency()).isEqualTo("CNY");
        assertThat(report.getOriginalAmount()).isEqualByComparingTo("1000");
        assertThat(report.getCnyAmount()).isEqualByComparingTo("1000");
        assertThat(report.getCostAmount()).isEqualByComparingTo("100.00");
        assertThat(report.getProfitAmount()).isEqualByComparingTo("900.00");
        // 利润率 = 900 / 1000 = 0.900000
        assertThat(report.getProfitRate()).isEqualByComparingTo("0.900000");
        assertThat(report.getPeriod()).isEqualTo("202607");
    }

    @Test
    @DisplayName("核算正确性：USD 订单按汇率折算 CNY 后计算利润")
    void calculateUsdOrderProfit() {
        List<RawOrder> orders = Collections.singletonList(
                buildOrder("ORD002", "Shopify", "USD", new BigDecimal("100"), new BigDecimal("7.25"))
        );
        when(rawOrderMapper.selectList(any())).thenReturn(orders);

        Map<String, BigDecimal> costMap = new HashMap<>();
        costMap.put("ORD002", new BigDecimal("7.25"));
        when(costAllocationService.allocate(anyString(), any(), anyString())).thenReturn(costMap);
        when(currencyConvertUtils.toCny(new BigDecimal("100"), "USD"))
                .thenReturn(new BigDecimal("725.000000"));

        List<ProfitReport> captured = new java.util.ArrayList<>();
        ProfitEngineServiceImpl service = createService(captured);

        service.calculate("202607");

        ProfitReport report = captured.get(0);
        assertThat(report.getCnyAmount()).isEqualByComparingTo("725.000000");
        assertThat(report.getCostAmount()).isEqualByComparingTo("7.25");
        assertThat(report.getProfitAmount()).isEqualByComparingTo("717.750000");
        // 利润率 = 717.75 / 725 = 0.990000
        assertThat(report.getProfitRate()).isEqualByComparingTo("0.990000");
    }

    @Test
    @DisplayName("多订单核算：分摊成本正确分配到各订单")
    void calculateMultipleOrders() {
        List<RawOrder> orders = Arrays.asList(
                buildOrder("O1", "Amazon", "CNY", new BigDecimal("100"), new BigDecimal("30")),
                buildOrder("O2", "Amazon", "CNY", new BigDecimal("200"), new BigDecimal("70"))
        );
        when(rawOrderMapper.selectList(any())).thenReturn(orders);

        // 总平台费 = 30 + 70 = 100，按金额占比分摊
        Map<String, BigDecimal> costMap = new HashMap<>();
        costMap.put("O1", new BigDecimal("33.33"));
        costMap.put("O2", new BigDecimal("66.67"));
        when(costAllocationService.allocate(eq("202607"), eq(new BigDecimal("100")), eq("AMOUNT")))
                .thenReturn(costMap);
        when(currencyConvertUtils.toCny(new BigDecimal("100"), "CNY")).thenReturn(new BigDecimal("100"));
        when(currencyConvertUtils.toCny(new BigDecimal("200"), "CNY")).thenReturn(new BigDecimal("200"));

        List<ProfitReport> captured = new java.util.ArrayList<>();
        ProfitEngineServiceImpl service = createService(captured);

        service.calculate("202607");

        assertThat(captured).hasSize(2);
        // O1: 100 - 33.33 = 66.67
        ProfitReport r1 = captured.stream().filter(r -> r.getOrderNo().equals("O1")).findFirst().orElseThrow();
        assertThat(r1.getProfitAmount()).isEqualByComparingTo("66.67");
        // O2: 200 - 66.67 = 133.33
        ProfitReport r2 = captured.stream().filter(r -> r.getOrderNo().equals("O2")).findFirst().orElseThrow();
        assertThat(r2.getProfitAmount()).isEqualByComparingTo("133.33");
    }

    // ==================== 幂等性 ====================
    @Test
    @DisplayName("幂等性：核算前先删除该周期旧报表")
    void calculateDeletesOldReportsFirst() {
        List<RawOrder> orders = Collections.singletonList(
                buildOrder("O1", "Amazon", "CNY", new BigDecimal("100"), new BigDecimal("10"))
        );
        when(rawOrderMapper.selectList(any())).thenReturn(orders);
        when(costAllocationService.allocate(anyString(), any(), anyString()))
                .thenReturn(Collections.emptyMap());
        when(currencyConvertUtils.toCny(any(), anyString())).thenReturn(new BigDecimal("100"));

        ProfitEngineServiceImpl service = createService(null);
        service.calculate("202607");

        // 必须先调用 delete 删除旧报表
        verify(profitReportMapper).delete(any());
    }

    @Test
    @DisplayName("金额为 0 的订单：利润率为 0，不抛除零异常")
    void calculateZeroAmountOrder() {
        List<RawOrder> orders = Collections.singletonList(
                buildOrder("ZERO", "Amazon", "CNY", BigDecimal.ZERO, new BigDecimal("10"))
        );
        when(rawOrderMapper.selectList(any())).thenReturn(orders);
        when(costAllocationService.allocate(anyString(), any(), anyString()))
                .thenReturn(Collections.singletonMap("ZERO", new BigDecimal("10")));
        when(currencyConvertUtils.toCny(BigDecimal.ZERO, "CNY")).thenReturn(BigDecimal.ZERO);

        List<ProfitReport> captured = new java.util.ArrayList<>();
        ProfitEngineServiceImpl service = createService(captured);

        service.calculate("202607"); // 不应抛除零异常

        ProfitReport report = captured.get(0);
        assertThat(report.getProfitRate()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.getProfitAmount()).isEqualByComparingTo(new BigDecimal("-10"));
    }

    @Test
    @DisplayName("fee 为 null 的订单：成本池按 0 处理")
    void calculateNullFeeOrder() {
        List<RawOrder> orders = Collections.singletonList(
                buildOrder("O1", "Amazon", "CNY", new BigDecimal("100"), null)
        );
        when(rawOrderMapper.selectList(any())).thenReturn(orders);
        when(costAllocationService.allocate(anyString(), eq(BigDecimal.ZERO), anyString()))
                .thenReturn(Collections.emptyMap());
        when(currencyConvertUtils.toCny(new BigDecimal("100"), "CNY")).thenReturn(new BigDecimal("100"));

        List<ProfitReport> captured = new java.util.ArrayList<>();
        ProfitEngineServiceImpl service = createService(captured);

        service.calculate("202607");

        // 总成本为 0，利润 = 100 - 0 = 100
        assertThat(captured.get(0).getCostAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(captured.get(0).getProfitAmount()).isEqualByComparingTo(new BigDecimal("100"));
    }
}
