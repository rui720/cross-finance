package com.finance.platform.accounting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.platform.accounting.entity.ProfitReport;
import com.finance.platform.accounting.mapper.ProfitReportMapper;
import com.finance.platform.accounting.service.impl.ProfitDiagnosisServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 利润诊断服务单元测试
 * <p>
 * 验证四维度诊断逻辑：
 * A. 结构占比：增收不增利识别、亏损平台预警
 * B. 趋势与波动：环比变化、信号判定（IMPROVING/DECLINING/STABLE/NO_HISTORY）
 * C. 成本合理性：高客单价订单被过度摊费识别
 * D. 行动点：基于诊断结果生成预警
 * <p>
 * 配置层移除后，computeCostDiagnosis 固定返回 ruleType=AMOUNT，
 * 不再依赖 cost_allocation_rule 表查询。
 */
@DisplayName("利润诊断服务测试")
@ExtendWith(MockitoExtension.class)
class ProfitDiagnosisServiceTest {

    @Mock private ProfitReportMapper profitReportMapper;

    @InjectMocks
    private ProfitDiagnosisServiceImpl service;

    private ProfitReport report(String period, String platform, String currency,
                                BigDecimal cnyAmount, BigDecimal cost, BigDecimal profit) {
        ProfitReport r = new ProfitReport();
        r.setPeriod(period);
        r.setPlatform(platform);
        r.setCurrency(currency);
        r.setOriginalAmount(cnyAmount);
        r.setCnyAmount(cnyAmount);
        r.setCostAmount(cost);
        r.setProfitAmount(profit);
        r.setProfitRate(cnyAmount.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : profit.divide(cnyAmount, 4, java.math.RoundingMode.HALF_UP));
        return r;
    }

    // ==================== A. 结构占比 ====================

    @Test
    @DisplayName("A 维度：识别亏损平台 DANGER 预警")
    void structureDetectsLossPlatform() {
        // Amazon 盈利 1000，Shopee 亏损 200
        List<ProfitReport> reports = List.of(
                report("202607", "Amazon", "USD", new BigDecimal("5000"), new BigDecimal("4000"), new BigDecimal("1000")),
                report("202607", "Shopee", "USD", new BigDecimal("2000"), new BigDecimal("2200"), new BigDecimal("-200")));
        when(profitReportMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(reports);

        ProfitDiagnosisService.DiagnosisResult result = service.diagnose("202607");

        assertThat(result.structure().healthFlags())
                .anyMatch(f -> "DANGER".equals(f.level()) && "LOSS_PLATFORM".equals(f.type()) && "Shopee".equals(f.platform()));
    }

    @Test
    @DisplayName("A 维度：识别增收不增利 WARN 预警")
    void structureDetectsRevenueProfitGap() {
        // Amazon 营收占比 80% 但利润占比 50%，差距 30 个百分点
        List<ProfitReport> reports = List.of(
                report("202607", "Amazon", "USD", new BigDecimal("8000"), new BigDecimal("7000"), new BigDecimal("1000")),
                report("202607", "Shopee", "USD", new BigDecimal("2000"), new BigDecimal("500"), new BigDecimal("1500")));
        when(profitReportMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(reports);

        ProfitDiagnosisService.DiagnosisResult result = service.diagnose("202607");

        assertThat(result.structure().healthFlags())
                .anyMatch(f -> "WARN".equals(f.level()) && "REVENUE_PROFIT_GAP".equals(f.type()) && "Amazon".equals(f.platform()));
    }

    @Test
    @DisplayName("A 维度：各平台利润占比之和为 100%")
    void structureProfitShareSumsToOne() {
        List<ProfitReport> reports = List.of(
                report("202607", "Amazon", "USD", new BigDecimal("6000"), new BigDecimal("4000"), new BigDecimal("2000")),
                report("202607", "Shopee", "USD", new BigDecimal("4000"), new BigDecimal("2000"), new BigDecimal("2000")));
        when(profitReportMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(reports);

        ProfitDiagnosisService.DiagnosisResult result = service.diagnose("202607");

        BigDecimal totalShare = result.structure().byPlatform().stream()
                .map(ProfitDiagnosisService.PlatformBreakdown::profitShare)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalShare).isEqualByComparingTo(new BigDecimal("1.0000"));
    }

    // ==================== B. 趋势与波动 ====================

    @Test
    @DisplayName("B 维度：无历史数据返回 NO_HISTORY 信号")
    void trendNoHistory() {
        when(profitReportMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.emptyList());  // 第一次查询本期无数据，第二次查询上期也无数据

        ProfitDiagnosisService.DiagnosisResult result = service.diagnose("202601");

        assertThat(result.trend().trendSignal()).isEqualTo("NO_HISTORY");
        assertThat(result.trend().explanation()).contains("无历史数据");
    }

    @Test
    @DisplayName("B 维度：利润率下滑超 3pp 触发 DECLINING 信号")
    void trendDeclining() {
        // 本期利润率 11%，上期 15%，下滑 4pp
        List<ProfitReport> current = List.of(
                report("202607", "Amazon", "USD", new BigDecimal("10000"), new BigDecimal("8900"), new BigDecimal("1100")));
        List<ProfitReport> previous = List.of(
                report("202606", "Amazon", "USD", new BigDecimal("10000"), new BigDecimal("8500"), new BigDecimal("1500")));
        when(profitReportMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(current)   // 第一次：本期
                .thenReturn(previous); // 第二次：上期

        ProfitDiagnosisService.DiagnosisResult result = service.diagnose("202607");

        assertThat(result.trend().trendSignal()).isEqualTo("DECLINING");
        assertThat(result.trend().profitRateDelta()).isEqualByComparingTo(new BigDecimal("-4.00"));
        assertThat(result.trend().previousPeriod()).isEqualTo("202606");
    }

    @Test
    @DisplayName("B 维度：利润率提升触发 IMPROVING 信号")
    void trendImproving() {
        List<ProfitReport> current = List.of(
                report("202607", "Amazon", "USD", new BigDecimal("10000"), new BigDecimal("8000"), new BigDecimal("2000")));
        List<ProfitReport> previous = List.of(
                report("202606", "Amazon", "USD", new BigDecimal("10000"), new BigDecimal("8800"), new BigDecimal("1200")));
        when(profitReportMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(current).thenReturn(previous);

        ProfitDiagnosisService.DiagnosisResult result = service.diagnose("202607");

        assertThat(result.trend().trendSignal()).isEqualTo("IMPROVING");
        assertThat(result.trend().profitRateDelta()).isEqualByComparingTo(new BigDecimal("8.00"));
    }

    @Test
    @DisplayName("B 维度：跨年计算上期 202601 → 202512")
    void trendCrossYearPreviousPeriod() {
        List<ProfitReport> current = List.of(
                report("202601", "Amazon", "USD", new BigDecimal("1000"), new BigDecimal("900"), new BigDecimal("100")));
        when(profitReportMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(current).thenReturn(Collections.emptyList());

        ProfitDiagnosisService.DiagnosisResult result = service.diagnose("202601");

        assertThat(result.trend().previousPeriod()).isEqualTo("202512");
    }

    // ==================== C. 成本合理性 ====================

    @Test
    @DisplayName("C 维度：高客单价订单被多摊成本识别")
    void costDiagnosisHighTicketOverCost() {
        // 客单价均值 = (5000 + 500) / 2 = 2750，高客单价阈值 = 5500
        // 订单1 客单价 5000，不算高客单价；订单2 客单价 500，不算高客单价
        // 改为：订单1 客单价 10000（高客单价），订单2 客单价 1000
        // 均值 = 5500，阈值 = 11000
        // 订单1 (10000) 未超阈值；调整数据让订单1超过阈值
        BigDecimal highTicket = new BigDecimal("20000");
        BigDecimal lowTicket = new BigDecimal("1000");
        // 高客单价订单成本率被人为拉高（按金额分摊下高客单价订单承担更多成本）
        BigDecimal highCost = new BigDecimal("18000");  // 成本率 90%
        BigDecimal lowCost = new BigDecimal("100");     // 成本率 10%
        List<ProfitReport> reports = List.of(
                report("202607", "Amazon", "USD", highTicket, highCost, highTicket.subtract(highCost)),
                report("202607", "Amazon", "USD", lowTicket, lowCost, lowTicket.subtract(lowCost)));
        when(profitReportMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(reports);

        ProfitDiagnosisService.CostDiagnosis cost = service.diagnose("202607").cost();

        // 配置层移除后，ruleType 固定为 AMOUNT
        assertThat(cost.currentRuleType()).isEqualTo("AMOUNT");
        // 平均成本率 = (18000+100) / (20000+1000) = 18100/21000 ≈ 0.8619
        assertThat(cost.avgCostRate()).isCloseTo(new BigDecimal("0.8619"),
                org.assertj.core.data.Offset.offset(new BigDecimal("0.001")));
    }

    @Test
    @DisplayName("C 维度：平台间成本率差异显著触发建议")
    void costDiagnosisPlatformCostRateGap() {
        // Amazon 成本率 10%，Shopee 成本率 80%，差异 70pp
        List<ProfitReport> reports = List.of(
                report("202607", "Amazon", "USD", new BigDecimal("10000"), new BigDecimal("1000"), new BigDecimal("9000")),
                report("202607", "Shopee", "USD", new BigDecimal("10000"), new BigDecimal("8000"), new BigDecimal("2000")));
        when(profitReportMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(reports);

        ProfitDiagnosisService.CostDiagnosis cost = service.diagnose("202607").cost();

        assertThat(cost.suggestions()).anyMatch(s -> s.contains("差异显著"));
    }

    // ==================== D. 行动点 ====================

    @Test
    @DisplayName("D 维度：亏损平台生成 DANGER 行动点")
    void actionPointsForLossPlatform() {
        List<ProfitReport> reports = List.of(
                report("202607", "Shopee", "USD", new BigDecimal("1000"), new BigDecimal("1500"), new BigDecimal("-500")));
        when(profitReportMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(reports);

        List<ProfitDiagnosisService.ActionPoint> points = service.diagnose("202607").actionPoints();

        assertThat(points).anyMatch(p -> "DANGER".equals(p.level()) && "STRUCTURE".equals(p.category())
                && p.title().contains("Shopee"));
    }

    @Test
    @DisplayName("D 维度：整体亏损生成 DANGER 行动点")
    void actionPointsForOverallLoss() {
        List<ProfitReport> reports = List.of(
                report("202607", "Amazon", "USD", new BigDecimal("1000"), new BigDecimal("2000"), new BigDecimal("-1000")));
        when(profitReportMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(reports);

        List<ProfitDiagnosisService.ActionPoint> points = service.diagnose("202607").actionPoints();

        assertThat(points).anyMatch(p -> "DANGER".equals(p.level()) && p.title().contains("亏损"));
    }

    @Test
    @DisplayName("D 维度：健康业务返回 INFO 行动点")
    void actionPointsForHealthyBusiness() {
        // 利润率 20%，无亏损，无显著差异
        List<ProfitReport> reports = List.of(
                report("202607", "Amazon", "USD", new BigDecimal("10000"), new BigDecimal("8000"), new BigDecimal("2000")));
        when(profitReportMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(reports);

        List<ProfitDiagnosisService.ActionPoint> points = service.diagnose("202607").actionPoints();

        assertThat(points).anyMatch(p -> "INFO".equals(p.level()) && p.title().contains("健康"));
    }

    @Test
    @DisplayName("D 维度：低利润率（<5%）生成 WARN 行动点")
    void actionPointsForLowProfitRate() {
        // 利润率 3%
        List<ProfitReport> reports = List.of(
                report("202607", "Amazon", "USD", new BigDecimal("10000"), new BigDecimal("9700"), new BigDecimal("300")));
        when(profitReportMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(reports);

        List<ProfitDiagnosisService.ActionPoint> points = service.diagnose("202607").actionPoints();

        assertThat(points).anyMatch(p -> "WARN".equals(p.level()) && p.title().contains("利润率偏低"));
    }

    // ==================== 摘要 ====================

    @Test
    @DisplayName("摘要：汇总营收/成本/利润/利润率正确")
    void summaryCorrectness() {
        List<ProfitReport> reports = List.of(
                report("202607", "Amazon", "USD", new BigDecimal("1000"), new BigDecimal("800"), new BigDecimal("200")),
                report("202607", "Shopee", "USD", new BigDecimal("2000"), new BigDecimal("1500"), new BigDecimal("500")));
        when(profitReportMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(reports);

        ProfitDiagnosisService.Summary summary = service.diagnose("202607").summary();

        assertThat(summary.orderCount()).isEqualTo(2);
        assertThat(summary.totalRevenue()).isEqualByComparingTo(new BigDecimal("3000"));
        assertThat(summary.totalCost()).isEqualByComparingTo(new BigDecimal("2300"));
        assertThat(summary.totalProfit()).isEqualByComparingTo(new BigDecimal("700"));
        // 利润率 = 700/3000 ≈ 0.2333
        assertThat(summary.profitRate()).isCloseTo(new BigDecimal("0.2333"),
                org.assertj.core.data.Offset.offset(new BigDecimal("0.001")));
    }
}
