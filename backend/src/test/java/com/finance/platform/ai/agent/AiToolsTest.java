package com.finance.platform.ai.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.platform.accounting.entity.CostAllocationRule;
import com.finance.platform.accounting.entity.ProfitReport;
import com.finance.platform.accounting.mapper.CostAllocationRuleMapper;
import com.finance.platform.accounting.service.ProfitEngineService;
import com.finance.platform.ai.service.AiAnalysisService;
import com.finance.platform.common.utils.CurrencyConvertUtils;
import com.finance.platform.data.entity.ExchangeRateSnapshot;
import com.finance.platform.data.entity.RawOrder;
import com.finance.platform.data.mapper.ExchangeRateSnapshotMapper;
import com.finance.platform.data.mapper.RawOrderMapper;
import com.finance.platform.data.service.ExchangeRateService;
import com.finance.platform.fund.entity.BudgetPlan;
import com.finance.platform.fund.entity.PaymentApply;
import com.finance.platform.fund.service.BudgetControlService;
import com.finance.platform.fund.service.PaymentFlowService;
import com.finance.platform.system.entity.SysAuditLog;
import com.finance.platform.system.service.SysAuditLogService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * AI Agent 工具集单元测试
 * <p>
 * 验证 12 个 @Tool 方法的核心逻辑：
 * 1. 汇率查询：getLatestExchangeRate（含金额换算）/ getExchangeRateHistory
 * 2. 业务查询：queryOrders / queryProfitReport / queryBudgetWarnings / queryPaymentApplies
 * 3. 详情查询：queryAllocationRules / queryPaymentDetail / queryOrderDetail
 * 4. 日志与对账：queryAuditLogs / queryReconcileStatus
 * 5. AI 分析：analyzeProfit
 * <p>
 * 同时验证 @Tool 注解被正确识别（工具规格提取）。
 */
@DisplayName("AI Agent 工具集测试")
@ExtendWith(MockitoExtension.class)
class AiToolsTest {

    @Mock private ExchangeRateService exchangeRateService;
    @Mock private ExchangeRateSnapshotMapper exchangeRateSnapshotMapper;
    @Mock private RawOrderMapper rawOrderMapper;
    @Mock private ProfitEngineService profitEngineService;
    @Mock private BudgetControlService budgetControlService;
    @Mock private PaymentFlowService paymentFlowService;
    @Mock private CurrencyConvertUtils currencyConvertUtils;
    @Mock private AiAnalysisService aiAnalysisService;
    @Mock private SysAuditLogService sysAuditLogService;
    @Mock private CostAllocationRuleMapper costAllocationRuleMapper;

    @InjectMocks
    private AiTools aiTools;

    @BeforeEach
    void setUp() {
        // 注入测试汇率到 currencyConvertUtils（用 lenient 因为大多数测试不调用此 mock）
        lenient().when(currencyConvertUtils.convert(any(BigDecimal.class), anyString(), anyString()))
                .thenAnswer(inv -> {
                    BigDecimal amount = inv.getArgument(0);
                    String from = inv.getArgument(1);
                    String to = inv.getArgument(2);
                    if (from.equals(to)) return amount;
                    // 简单模拟：USD->CNY = 7.25
                    if ("USD".equals(from) && "CNY".equals(to)) return amount.multiply(new BigDecimal("7.25"));
                    return amount;
                });
    }

    // ==================== 工具注解验证 ====================

    @Test
    @DisplayName("所有 @Tool 方法被正确识别为工具规格")
    void allToolsAnnotated() {
        List<ToolSpecification> specs = ToolSpecifications.toolSpecificationsFrom(aiTools);
        // 应有 12 个工具（合并后：原13 - convertCurrency = 12）
        assertThat(specs).hasSize(12);
        // 验证工具名（方法名）
        List<String> toolNames = specs.stream().map(ToolSpecification::name).toList();
        assertThat(toolNames).containsExactlyInAnyOrder(
                "getLatestExchangeRate", "getExchangeRateHistory",
                "queryOrders", "queryProfitReport", "queryBudgetWarnings", "queryPaymentApplies",
                "queryAllocationRules", "queryPaymentDetail", "queryOrderDetail",
                "queryAuditLogs", "queryReconcileStatus", "analyzeProfit"
        );
    }

    // ==================== 1. 汇率查询 ====================

    @Test
    @DisplayName("查询最新汇率：不传金额，返回汇率文本")
    void getLatestExchangeRate() {
        when(exchangeRateService.getLatestRate("USD", "CNY"))
                .thenReturn(new BigDecimal("7.25000000"));
        String result = aiTools.getLatestExchangeRate("USD", "CNY", null);
        // 不传金额时只返回汇率，不包含换算结果
        assertThat(result).contains("7.25000000").contains("USD").contains("CNY").doesNotContain("折算");
    }

    @Test
    @DisplayName("查询最新汇率：带金额，返回换算结果")
    void getLatestExchangeRateWithAmount() {
        when(exchangeRateService.getLatestRate("USD", "CNY"))
                .thenReturn(new BigDecimal("7.25000000"));
        String result = aiTools.getLatestExchangeRate("USD", "CNY", new BigDecimal("1000"));
        assertThat(result).contains("7.25000000").contains("1000").contains("7250");
    }

    @Test
    @DisplayName("查询最新汇率：异常时返回失败提示")
    void getLatestExchangeRateError() {
        when(exchangeRateService.getLatestRate(anyString(), anyString()))
                .thenThrow(new RuntimeException("汇率不存在"));
        String result = aiTools.getLatestExchangeRate("XXX", "CNY", null);
        assertThat(result).contains("查询汇率失败").contains("汇率不存在");
    }

    @Test
    @DisplayName("查询汇率历史：返回走势文本")
    void getExchangeRateHistory() {
        ExchangeRateSnapshot s1 = new ExchangeRateSnapshot();
        s1.setRateDate(LocalDate.of(2026, 7, 1));
        s1.setRate(new BigDecimal("7.20"));
        ExchangeRateSnapshot s2 = new ExchangeRateSnapshot();
        s2.setRateDate(LocalDate.of(2026, 7, 10));
        s2.setRate(new BigDecimal("7.25"));
        when(exchangeRateSnapshotMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(s1, s2));
        String result = aiTools.getExchangeRateHistory("USD", 7);
        assertThat(result).contains("USD").contains("7.20").contains("7.25").contains("最高").contains("最低");
    }

    @Test
    @DisplayName("查询汇率历史：无数据时返回提示")
    void getExchangeRateHistoryEmpty() {
        when(exchangeRateSnapshotMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.emptyList());
        String result = aiTools.getExchangeRateHistory("XXX", 7);
        assertThat(result).contains("未查询到");
    }

    // ==================== 2. 订单查询 ====================

    @Test
    @DisplayName("查询订单：返回汇总文本")
    void queryOrders() {
        RawOrder o1 = new RawOrder();
        o1.setPlatform("Amazon"); o1.setCurrency("USD"); o1.setAmount(new BigDecimal("1500"));
        RawOrder o2 = new RawOrder();
        o2.setPlatform("Amazon"); o2.setCurrency("USD"); o2.setAmount(new BigDecimal("2200"));
        when(rawOrderMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(o1, o2));
        String result = aiTools.queryOrders("Amazon", 7);
        assertThat(result).contains("2 笔").contains("3700").contains("Amazon");
    }

    @Test
    @DisplayName("查询订单：无数据时返回提示")
    void queryOrdersEmpty() {
        when(rawOrderMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.emptyList());
        String result = aiTools.queryOrders("eBay", 7);
        assertThat(result).contains("未查询到");
    }

    // ==================== 3. 利润报表 ====================

    @Test
    @DisplayName("查询利润报表：返回汇总文本")
    void queryProfitReport() {
        ProfitReport r1 = new ProfitReport();
        r1.setPlatform("Amazon"); r1.setCnyAmount(new BigDecimal("10875"));
        r1.setCostAmount(new BigDecimal("1000")); r1.setProfitAmount(new BigDecimal("9875"));
        Page<ProfitReport> page = new Page<>(1, 200);
        page.setRecords(List.of(r1));
        when(profitEngineService.getReport("202607", 1, 200)).thenReturn(page);
        String result = aiTools.queryProfitReport("202607");
        assertThat(result).contains("202607").contains("1 笔").contains("10875").contains("9875");
    }

    @Test
    @DisplayName("查询利润报表：无数据时返回提示")
    void queryProfitReportEmpty() {
        Page<ProfitReport> page = new Page<>(1, 200);
        page.setRecords(Collections.emptyList());
        when(profitEngineService.getReport(anyString(), any(Integer.class), any(Integer.class)))
                .thenReturn(page);
        String result = aiTools.queryProfitReport("202607");
        assertThat(result).contains("暂无利润报表");
    }

    // ==================== 4. 预算预警 ====================

    @Test
    @DisplayName("查询预算预警：返回预警列表")
    void queryBudgetWarnings() {
        BudgetPlan p1 = new BudgetPlan();
        p1.setPlanName("物流预算"); p1.setPeriod("202607");
        p1.setTotalAmount(new BigDecimal("500000")); p1.setUsedAmount(new BigDecimal("410000"));
        p1.setCurrency("CNY"); p1.setWarningThreshold(new BigDecimal("80"));
        when(budgetControlService.getWarningPlans()).thenReturn(List.of(p1));
        String result = aiTools.queryBudgetWarnings();
        assertThat(result).contains("1 项预算达到预警").contains("物流预算").contains("82.00");
    }

    @Test
    @DisplayName("查询预算预警：无预警时返回提示")
    void queryBudgetWarningsEmpty() {
        when(budgetControlService.getWarningPlans()).thenReturn(Collections.emptyList());
        String result = aiTools.queryBudgetWarnings();
        assertThat(result).contains("没有预算达到预警");
    }

    // ==================== 5. 付款申请查询 ====================

    @Test
    @DisplayName("查询付款申请：返回列表文本")
    void queryPaymentApplies() {
        PaymentApply p1 = new PaymentApply();
        p1.setApplyNo("PAY-001"); p1.setAmount(new BigDecimal("50000"));
        p1.setCurrency("CNY"); p1.setStatus(1); p1.setPayee("物流公司"); p1.setApplyReason("运费");
        when(paymentFlowService.list(any(LambdaQueryWrapper.class))).thenReturn(List.of(p1));
        String result = aiTools.queryPaymentApplies(1);
        assertThat(result).contains("1 条").contains("PAY-001").contains("待审批").contains("50000");
    }

    @Test
    @DisplayName("查询付款申请：无数据时返回提示")
    void queryPaymentAppliesEmpty() {
        when(paymentFlowService.list(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());
        String result = aiTools.queryPaymentApplies(0);
        assertThat(result).contains("未查询到");
    }

    // ==================== 6. 分摊规则 ====================

    @Test
    @DisplayName("查询分摊规则：返回规则列表")
    void queryAllocationRules() {
        CostAllocationRule r1 = new CostAllocationRule();
        r1.setRuleName("按金额分摊"); r1.setRuleType("AMOUNT"); r1.setEnabled(1); r1.setDescription("默认规则");
        when(costAllocationRuleMapper.selectList(any())).thenReturn(List.of(r1));
        String result = aiTools.queryAllocationRules();
        assertThat(result).contains("1 条").contains("按金额分摊").contains("已启用");
    }

    @Test
    @DisplayName("查询分摊规则：无规则时返回提示")
    void queryAllocationRulesEmpty() {
        when(costAllocationRuleMapper.selectList(any())).thenReturn(Collections.emptyList());
        String result = aiTools.queryAllocationRules();
        assertThat(result).contains("未配置");
    }

    // ==================== 8. 付款详情 ====================

    @Test
    @DisplayName("查询付款详情：返回详情文本")
    void queryPaymentDetail() {
        PaymentApply p = new PaymentApply();
        p.setApplyNo("PAY-001"); p.setPayee("物流公司"); p.setAmount(new BigDecimal("50000"));
        p.setCurrency("CNY"); p.setApplyReason("运费"); p.setStatus(1);
        p.setApplyTime(LocalDateTime.now()); p.setBudgetPlanId(1L);
        when(paymentFlowService.getOne(any(LambdaQueryWrapper.class))).thenReturn(p);
        String result = aiTools.queryPaymentDetail("PAY-001");
        assertThat(result).contains("PAY-001").contains("物流公司").contains("50000").contains("待审批");
    }

    @Test
    @DisplayName("查询付款详情：不存在时返回提示")
    void queryPaymentDetailNotFound() {
        when(paymentFlowService.getOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        String result = aiTools.queryPaymentDetail("NOT-EXIST");
        assertThat(result).contains("未找到");
    }

    // ==================== 9. 订单详情 ====================

    @Test
    @DisplayName("查询订单详情：返回详情文本")
    void queryOrderDetail() {
        RawOrder o = new RawOrder();
        o.setOrderNo("ORD-001"); o.setPlatform("Amazon"); o.setShopId("SHOP001");
        o.setCurrency("USD"); o.setAmount(new BigDecimal("1500")); o.setFee(new BigDecimal("150"));
        o.setSettleAmount(new BigDecimal("10875")); o.setOrderTime(LocalDateTime.now());
        o.setSettleTime(LocalDateTime.now()); o.setSource("PLATFORM");
        when(rawOrderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(o);
        String result = aiTools.queryOrderDetail("ORD-001");
        assertThat(result).contains("ORD-001").contains("Amazon").contains("1500").contains("已结算");
    }

    @Test
    @DisplayName("查询订单详情：不存在时返回提示")
    void queryOrderDetailNotFound() {
        when(rawOrderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        String result = aiTools.queryOrderDetail("NOT-EXIST");
        assertThat(result).contains("未找到");
    }

    // ==================== 10. 审计日志 ====================

    @Test
    @DisplayName("查询审计日志：返回日志列表")
    void queryAuditLogs() {
        SysAuditLog log = new SysAuditLog();
        log.setCreateTime(LocalDateTime.now()); log.setUsername("admin");
        log.setOperation("新增用户"); log.setMethod("SysUserController.add");
        log.setCostTime(50L); log.setStatus(1);
        when(sysAuditLogService.list(any(LambdaQueryWrapper.class))).thenReturn(List.of(log));
        String result = aiTools.queryAuditLogs(7);
        assertThat(result).contains("admin").contains("新增用户").contains("50ms").contains("成功");
    }

    @Test
    @DisplayName("查询审计日志：无日志时返回提示")
    void queryAuditLogsEmpty() {
        when(sysAuditLogService.list(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());
        String result = aiTools.queryAuditLogs(7);
        assertThat(result).contains("无审计日志");
    }

    // ==================== 11. 对账状态 ====================

    @Test
    @DisplayName("查询对账状态：返回对账汇总")
    void queryReconcileStatus() {
        // selectCount 被调用两次：total=10, reconciled=8
        when(rawOrderMapper.selectCount(any(LambdaQueryWrapper.class)))
                .thenReturn(10L)   // 第一次：总数
                .thenReturn(8L);   // 第二次：已对账
        String result = aiTools.queryReconcileStatus();
        assertThat(result).contains("10 条流水").contains("已对账 8").contains("待对账 2").contains("80.00");
    }

    // ==================== 12. 利润归因分析 ====================

    @Test
    @DisplayName("利润归因分析：返回分析报告")
    void analyzeProfit() {
        when(aiAnalysisService.analyzeProfit("202607"))
                .thenReturn("7月利润归因分析：收入下降主要受汇率波动影响...");
        String result = aiTools.analyzeProfit("202607");
        assertThat(result).contains("7月利润归因").contains("汇率波动");
    }

    @Test
    @DisplayName("利润归因分析：无数据时返回提示")
    void analyzeProfitEmpty() {
        when(aiAnalysisService.analyzeProfit(anyString())).thenReturn("");
        String result = aiTools.analyzeProfit("202607");
        assertThat(result).contains("暂无利润数据");
    }
}
