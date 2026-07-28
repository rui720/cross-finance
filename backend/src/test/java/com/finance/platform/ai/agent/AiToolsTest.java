package com.finance.platform.ai.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.platform.accounting.entity.ProfitReport;
import com.finance.platform.accounting.service.ProfitEngineService;
import com.finance.platform.ai.service.AiAnalysisService;
import com.finance.platform.common.utils.CurrencyConvertUtils;
import com.finance.platform.data.entity.ExchangeRateSnapshot;
import com.finance.platform.data.entity.ImportBatch;
import com.finance.platform.data.entity.ImportTemplate;
import com.finance.platform.data.entity.RawOrder;
import com.finance.platform.data.mapper.ExchangeRateSnapshotMapper;
import com.finance.platform.data.mapper.RawOrderMapper;
import com.finance.platform.data.service.ExchangeRateService;
import com.finance.platform.data.service.ImportBatchService;
import com.finance.platform.data.service.ImportTemplateService;
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
 * 验证 11 个 @Tool 方法的核心逻辑：
 * 1. 汇率查询：getLatestExchangeRate（含金额换算）/ getExchangeRateHistory
 * 2. 业务查询：queryOrders / queryProfitReport / queryAllocationRules / queryOrderDetail
 * 3. 日志与对账：queryAuditLogs / queryReconcileStatus
 * 4. 导入管理：queryImportBatches / queryImportTemplates
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
    @Mock private CurrencyConvertUtils currencyConvertUtils;
    @Mock private AiAnalysisService aiAnalysisService;
    @Mock private SysAuditLogService sysAuditLogService;
    @Mock private ImportBatchService importBatchService;
    @Mock private ImportTemplateService importTemplateService;

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
        // 应有 11 个工具（原12 - 删除3个 fund 工具 + 新增 queryImportBatches + queryImportTemplates）
        assertThat(specs).hasSize(11);
        // 验证工具名（方法名）
        List<String> toolNames = specs.stream().map(ToolSpecification::name).toList();
        assertThat(toolNames).containsExactlyInAnyOrder(
                "getLatestExchangeRate", "getExchangeRateHistory",
                "queryOrders", "queryProfitReport",
                "queryAllocationRules", "queryOrderDetail",
                "queryAuditLogs", "queryReconcileStatus", "analyzeProfit",
                "queryImportBatches", "queryImportTemplates"
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
        when(profitEngineService.getReport("202607", null, null, 1, 200)).thenReturn(page);
        String result = aiTools.queryProfitReport("202607");
        assertThat(result).contains("202607").contains("1 笔").contains("10875").contains("9875");
    }

    @Test
    @DisplayName("查询利润报表：无数据时返回提示")
    void queryProfitReportEmpty() {
        Page<ProfitReport> page = new Page<>(1, 200);
        page.setRecords(Collections.emptyList());
        when(profitEngineService.getReport(anyString(), any(), any(), any(Integer.class), any(Integer.class)))
                .thenReturn(page);
        String result = aiTools.queryProfitReport("202607");
        assertThat(result).contains("暂无利润报表");
    }

    // ==================== 6. 分摊规则 ====================

    @Test
    @DisplayName("查询分摊规则：返回固定分摊策略说明")
    void queryAllocationRules() {
        // 配置层已移除，工具方法返回固定的分摊策略说明
        String result = aiTools.queryAllocationRules();
        assertThat(result).contains("金额占比").contains("分摊");
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
        log.setOperation("新增用户");
        log.setStatus(1);
        when(sysAuditLogService.list(any(LambdaQueryWrapper.class))).thenReturn(List.of(log));
        String result = aiTools.queryAuditLogs(7);
        assertThat(result).contains("admin").contains("新增用户").contains("成功");
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

    // ==================== 13. 导入批次状态查询（方案 B+D 新增） ====================

    @Test
    @DisplayName("查询导入批次：返回批次列表汇总")
    void queryImportBatches() {
        ImportBatch b1 = new ImportBatch();
        b1.setBatchNo("IMP-20260719-001"); b1.setFileName("amazon_july.xlsx");
        b1.setSourceType("PLATFORM"); b1.setStatus("CLEANED");
        b1.setTotalCount(100); b1.setSuccessCount(95); b1.setFailedCount(5);
        ImportBatch b2 = new ImportBatch();
        b2.setBatchNo("IMP-20260719-002"); b2.setFileName("bank_flow.csv");
        b2.setSourceType("BANK"); b2.setStatus("IMPORTED");
        b2.setTotalCount(50); b2.setSuccessCount(0); b2.setFailedCount(0);
        when(importBatchService.listAll()).thenReturn(List.of(b1, b2));
        String result = aiTools.queryImportBatches(10);
        assertThat(result)
                .contains("2 条导入批次")
                .contains("IMP-20260719-001").contains("amazon_july.xlsx").contains("CLEANED")
                .contains("IMP-20260719-002").contains("bank_flow.csv").contains("IMPORTED");
    }

    @Test
    @DisplayName("查询导入批次：无批次时返回提示")
    void queryImportBatchesEmpty() {
        when(importBatchService.listAll()).thenReturn(Collections.emptyList());
        String result = aiTools.queryImportBatches(10);
        assertThat(result).contains("暂无导入批次");
    }

    // ==================== 14. 导入模板配置查询（方案 B+D 新增） ====================

    @Test
    @DisplayName("查询导入模板：指定来源返回模板列表")
    void queryImportTemplates() {
        ImportTemplate t1 = new ImportTemplate();
        t1.setId(1L); t1.setTemplateName("Amazon 平台账单模板");
        t1.setPlatform("Amazon"); t1.setSourceType("PLATFORM");
        t1.setFileType("EXCEL"); t1.setAiGenerated(0);
        t1.setColumnMapping("{\"orderNo\":\"订单号\",\"amount\":\"金额\"}");
        t1.setCleanRules("trimRule,defaultCurrencyRule,filterInvalidRule");
        when(importTemplateService.listBySource("PLATFORM")).thenReturn(List.of(t1));
        String result = aiTools.queryImportTemplates("PLATFORM");
        assertThat(result)
                .contains("1 个导入模板")
                .contains("Amazon 平台账单模板").contains("Amazon")
                .contains("EXCEL").contains("订单号");
    }

    @Test
    @DisplayName("查询导入模板：来源为空时合并 PLATFORM 与 BANK")
    void queryImportTemplatesAll() {
        ImportTemplate t1 = new ImportTemplate();
        t1.setId(1L); t1.setTemplateName("平台模板"); t1.setSourceType("PLATFORM");
        t1.setFileType("EXCEL"); t1.setColumnMapping("{}"); t1.setAiGenerated(1);
        ImportTemplate t2 = new ImportTemplate();
        t2.setId(2L); t2.setTemplateName("银行模板"); t2.setSourceType("BANK");
        t2.setFileType("CSV"); t2.setColumnMapping("{}"); t2.setAiGenerated(0);
        when(importTemplateService.listBySource("PLATFORM")).thenReturn(List.of(t1));
        when(importTemplateService.listBySource("BANK")).thenReturn(List.of(t2));
        String result = aiTools.queryImportTemplates(null);
        assertThat(result).contains("2 个导入模板").contains("平台模板").contains("银行模板");
    }

    @Test
    @DisplayName("查询导入模板：无模板时返回提示")
    void queryImportTemplatesEmpty() {
        when(importTemplateService.listBySource(anyString())).thenReturn(Collections.emptyList());
        String result = aiTools.queryImportTemplates("PLATFORM");
        assertThat(result).contains("暂无导入模板");
    }
}
