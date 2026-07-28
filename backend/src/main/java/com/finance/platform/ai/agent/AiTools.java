package com.finance.platform.ai.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.platform.accounting.entity.ProfitReport;
import com.finance.platform.accounting.service.ProfitEngineService;
import com.finance.platform.ai.service.AiAnalysisService;
import com.finance.platform.common.utils.CurrencyConvertUtils;
import com.finance.platform.data.entity.ExchangeRateSnapshot;
import com.finance.platform.data.entity.RawOrder;
import com.finance.platform.data.mapper.ExchangeRateSnapshotMapper;
import com.finance.platform.data.mapper.RawOrderMapper;
import com.finance.platform.data.service.ExchangeRateService;
import com.finance.platform.data.entity.ImportBatch;
import com.finance.platform.data.entity.ImportTemplate;
import com.finance.platform.data.service.ImportBatchService;
import com.finance.platform.data.service.ImportTemplateService;
import com.finance.platform.system.entity.SysAuditLog;
import com.finance.platform.system.service.SysAuditLogService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI 顾问 Agent 工具集
 * <p>
 * 通过 LangChain4j {@code @Tool} 注解将业务查询能力暴露给大模型，
 * 使 AI 顾问能在对话中主动调用工具获取实时数据（汇率、订单、利润、预算、付款等），
 * 基于真实数据回答用户问题，而非仅依赖训练知识。
 * <p>
 * 工具方法返回 String（非任意对象），由模型自行解析文本内容组织回答。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiTools {

    private final ExchangeRateService exchangeRateService;
    private final ExchangeRateSnapshotMapper exchangeRateSnapshotMapper;
    private final RawOrderMapper rawOrderMapper;
    private final ProfitEngineService profitEngineService;
    private final CurrencyConvertUtils currencyConvertUtils;
    private final AiAnalysisService aiAnalysisService;
    private final SysAuditLogService sysAuditLogService;
    private final ImportBatchService importBatchService;
    private final ImportTemplateService importTemplateService;

    /**
     * 查询最新汇率（支持带金额换算）
     * <p>
     * 合并了原 convertCurrency 的能力：
     * - 不传 amount：返回汇率数值（如"1 USD = 7.25 CNY"）
     * - 传 amount：返回换算结果（如"1000 USD = 7250 CNY"）
     */
    @Tool("查询当前最新汇率，或按最新汇率换算具体金额。参数：fromCurrency 源币种代码（如 USD/EUR/HKD/JPY/CNY），toCurrency 目标币种代码（如 CNY），amount 可选，需要换算具体金额时传入（如 1000 表示换算1000美元）。不传amount只返回汇率，传amount返回换算结果。")
    public String getLatestExchangeRate(
            @P("源币种代码，如 USD") String fromCurrency,
            @P("目标币种代码，如 CNY") String toCurrency,
            @P("需要换算的金额，可选。如 1000 表示换算1000美元。不传则只返回汇率") BigDecimal amount) {
        try {
            BigDecimal rate = exchangeRateService.getLatestRate(fromCurrency, toCurrency);
            if (amount == null) {
                return String.format("当前最新汇率：1 %s = %s %s", fromCurrency, rate, toCurrency);
            }
            // 带金额，做换算
            BigDecimal result = currencyConvertUtils.convert(amount, fromCurrency, toCurrency);
            return String.format("当前最新汇率：1 %s = %s %s；%s %s = %s %s（按当前汇率折算）",
                    fromCurrency, rate, toCurrency,
                    amount.setScale(2, RoundingMode.HALF_UP), fromCurrency,
                    result.setScale(2, RoundingMode.HALF_UP), toCurrency);
        } catch (Exception e) {
            log.warn("[AiTool] 查询汇率/换算失败 {} -> {} (amount={}): {}", fromCurrency, toCurrency, amount, e.getMessage());
            return "查询汇率失败：" + e.getMessage();
        }
    }

    /**
     * 查询汇率历史走势
     */
    @Tool("查询某币种对人民币的历史汇率走势。参数：fromCurrency 源币种代码（如 USD），days 最近多少天的汇率（如 7、30），默认7天。返回每日汇率列表。")
    public String getExchangeRateHistory(
            @P("源币种代码，如 USD") String fromCurrency,
            @P("查询天数，如 7 或 30") Integer days) {
        try {
            int queryDays = days == null || days <= 0 ? 7 : Math.min(days, 90);
            LocalDate endDate = LocalDate.now();
            LocalDate startDate = endDate.minusDays(queryDays);
            List<ExchangeRateSnapshot> list = exchangeRateSnapshotMapper.selectList(
                    new LambdaQueryWrapper<ExchangeRateSnapshot>()
                            .eq(ExchangeRateSnapshot::getFromCurrency, fromCurrency)
                            .eq(ExchangeRateSnapshot::getToCurrency, "CNY")
                            .between(ExchangeRateSnapshot::getRateDate, startDate, endDate)
                            .orderByAsc(ExchangeRateSnapshot::getRateDate));
            if (list.isEmpty()) {
                return String.format("未查询到 %s 近 %d 天的汇率记录", fromCurrency, queryDays);
            }
            String trend = list.stream()
                    .map(s -> s.getRateDate().format(DateTimeFormatter.ISO_DATE) + ": " + s.getRate())
                    .collect(Collectors.joining("; "));
            BigDecimal max = list.stream().map(ExchangeRateSnapshot::getRate).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            BigDecimal min = list.stream().map(ExchangeRateSnapshot::getRate).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            return String.format("%s 兑 CNY 近 %d 天汇率走势：%s；最高 %s，最低 %s",
                    fromCurrency, queryDays, trend, max, min);
        } catch (Exception e) {
            return "查询汇率历史失败：" + e.getMessage();
        }
    }

    /**
     * 查询订单/账单数据
     */
    @Tool("查询平台账单订单数据。参数：platform 平台名称（如 Amazon/Shopee/eBay/Rakuten，可为空查全部），days 最近多少天的订单（如 7、30，默认30）。返回订单数量、总金额、币种分布等汇总。")
    public String queryOrders(
            @P("平台名称，如 Amazon；为空查全部") String platform,
            @P("查询天数，如 7 或 30") Integer days) {
        try {
            int queryDays = days == null || days <= 0 ? 30 : Math.min(days, 365);
            LocalDate startDate = LocalDate.now().minusDays(queryDays);
            LambdaQueryWrapper<RawOrder> wrapper = new LambdaQueryWrapper<RawOrder>()
                    .eq(RawOrder::getSource, com.finance.platform.common.constant.BusinessConstants.SOURCE_PLATFORM)
                    .ge(RawOrder::getOrderTime, startDate.atStartOfDay());
            if (platform != null && !platform.isBlank()) {
                wrapper.eq(RawOrder::getPlatform, platform);
            }
            List<RawOrder> orders = rawOrderMapper.selectList(wrapper);
            if (orders.isEmpty()) {
                return String.format("未查询到%s近 %d 天的订单数据",
                        platform == null || platform.isBlank() ? "" : platform + " ", queryDays);
            }
            BigDecimal totalAmount = orders.stream().map(RawOrder::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            Map<String, BigDecimal> byCurrency = new HashMap<>();
            Map<String, Integer> byPlatform = new HashMap<>();
            for (RawOrder o : orders) {
                byCurrency.merge(o.getCurrency(), o.getAmount(), BigDecimal::add);
                byPlatform.merge(o.getPlatform() == null ? "未知" : o.getPlatform(), 1, Integer::sum);
            }
            String currencyStr = byCurrency.entrySet().stream()
                    .map(e -> e.getKey() + ":" + e.getValue().setScale(2, RoundingMode.HALF_UP))
                    .collect(Collectors.joining(", "));
            String platformStr = byPlatform.entrySet().stream()
                    .map(e -> e.getKey() + ":" + e.getValue() + "单")
                    .collect(Collectors.joining(", "));
            return String.format("近 %d 天%s订单共 %d 笔，总金额 %s；币种分布：%s；平台分布：%s",
                    queryDays, platform == null || platform.isBlank() ? "" : platform + " ",
                    orders.size(), totalAmount.setScale(2, RoundingMode.HALF_UP), currencyStr, platformStr);
        } catch (Exception e) {
            return "查询订单失败：" + e.getMessage();
        }
    }

    /**
     * 查询利润报表
     */
    @Tool("查询指定周期的利润核算报表数据（仅返回数值汇总）。参数：period 核算周期（格式 yyyyMM，如 202607）。返回总收入、总成本、总利润、平均利润率等汇总数据。适用于用户问'利润多少/利润情况/利润报表'等只需数据的场景。")
    public String queryProfitReport(@P("核算周期，格式 yyyyMM，如 202607") String period) {
        try {
            var page = profitEngineService.getReport(period, null, null, 1, 200);
            List<ProfitReport> records = page.getRecords();
            if (records.isEmpty()) {
                return String.format("周期 %s 暂无利润报表数据，请先执行利润核算", period);
            }
            BigDecimal totalRevenue = records.stream().map(ProfitReport::getCnyAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalCost = records.stream().map(ProfitReport::getCostAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalProfit = records.stream().map(ProfitReport::getProfitAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal avgRate = totalRevenue.compareTo(BigDecimal.ZERO) > 0
                    ? totalProfit.multiply(BigDecimal.valueOf(100)).divide(totalRevenue, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            Map<String, BigDecimal> byPlatform = new HashMap<>();
            for (ProfitReport r : records) {
                byPlatform.merge(r.getPlatform() == null ? "未知" : r.getPlatform(), r.getProfitAmount(), BigDecimal::add);
            }
            String platformProfit = byPlatform.entrySet().stream()
                    .map(e -> e.getKey() + ":" + e.getValue().setScale(2, RoundingMode.HALF_UP))
                    .collect(Collectors.joining(", "));
            return String.format("周期 %s 利润报表：共 %d 笔订单，总收入 %s CNY，总成本 %s CNY，总利润 %s CNY，平均利润率 %s%%；各平台利润：%s",
                    period, records.size(),
                    totalRevenue.setScale(2, RoundingMode.HALF_UP),
                    totalCost.setScale(2, RoundingMode.HALF_UP),
                    totalProfit.setScale(2, RoundingMode.HALF_UP),
                    avgRate, platformProfit);
        } catch (Exception e) {
            return "查询利润报表失败：" + e.getMessage();
        }
    }

    /**
     * 查询费用分摊规则
     */
    @Tool("查询当前系统的费用分摊规则。无需参数。返回分摊策略说明。")
    public String queryAllocationRules() {
        // 配置层已移除，核算引擎硬编码按订单金额占比分摊公共成本
        return "系统按订单金额占比分摊公共成本";
    }

    /**
     * 查询订单详情
     */
    @Tool("根据订单号查询单条订单详情。参数：orderNo 订单号（如 ORD-202607-0001）。返回订单的平台、金额、币种、状态等信息。")
    public String queryOrderDetail(@P("订单号，如 ORD-202607-0001") String orderNo) {
        try {
            RawOrder o = rawOrderMapper.selectOne(new LambdaQueryWrapper<RawOrder>()
                    .eq(RawOrder::getOrderNo, orderNo));
            if (o == null) {
                return "未找到订单号 " + orderNo;
            }
            String settleStatus = o.getSettleTime() != null ? "已结算（" + o.getSettleTime() + "）" : "未结算";
            return String.format("订单详情：%s，平台 %s，店铺 %s，币种 %s，金额 %s，平台费 %s，结算金额 %s，下单时间 %s，%s，来源 %s",
                    o.getOrderNo(), o.getPlatform(), o.getShopId(), o.getCurrency(),
                    o.getAmount().setScale(2, RoundingMode.HALF_UP),
                    o.getFee().setScale(2, RoundingMode.HALF_UP),
                    o.getSettleAmount() == null ? "未折算" : o.getSettleAmount().setScale(2, RoundingMode.HALF_UP),
                    o.getOrderTime(), settleStatus, o.getSource());
        } catch (Exception e) {
            return "查询订单详情失败：" + e.getMessage();
        }
    }

    /**
     * 查询审计日志
     */
    @Tool("查询最近若干天的系统操作审计日志。参数：days 查询天数（如 1、7，默认7，最大30）。返回最近的操作记录摘要。")
    public String queryAuditLogs(@P("查询天数，如 1 或 7") Integer days) {
        try {
            int queryDays = days == null || days <= 0 ? 7 : Math.min(days, 30);
            LocalDate startDate = LocalDate.now().minusDays(queryDays);
            List<SysAuditLog> logs = sysAuditLogService.list(new LambdaQueryWrapper<SysAuditLog>()
                    .ge(SysAuditLog::getCreateTime, startDate.atStartOfDay())
                    .orderByDesc(SysAuditLog::getCreateTime)
                    .last("LIMIT 20"));
            if (logs.isEmpty()) {
                return String.format("近 %d 天无审计日志记录", queryDays);
            }
            String detail = logs.stream()
                    .map(l -> String.format("[%s] %s %s（%s）",
                            l.getCreateTime() == null ? "" : l.getCreateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                            l.getUsername() == null ? "匿名" : l.getUsername(),
                            l.getOperation() == null ? "未知操作" : l.getOperation(),
                            l.getStatus() != null && l.getStatus() == 1 ? "成功" : "失败"))
                    .collect(Collectors.joining("; "));
            return String.format("近 %d 天审计日志（显示最近20条）：%s", queryDays, detail);
        } catch (Exception e) {
            return "查询审计日志失败：" + e.getMessage();
        }
    }

    /**
     * 查询平台对账状态
     */
    @Tool("查询银行流水对账状态，统计已对账与未对账的银行流水数量。无需参数。返回对账汇总情况。")
    public String queryReconcileStatus() {
        try {
            Long total = rawOrderMapper.selectCount(new LambdaQueryWrapper<RawOrder>()
                    .eq(RawOrder::getSource, com.finance.platform.common.constant.BusinessConstants.SOURCE_BANK));
            Long reconciled = rawOrderMapper.selectCount(new LambdaQueryWrapper<RawOrder>()
                    .eq(RawOrder::getSource, com.finance.platform.common.constant.BusinessConstants.SOURCE_BANK)
                    .eq(RawOrder::getReconcileStatus, RawOrder.RECONCILE_DONE));
            Long pending = total - reconciled;
            return String.format("银行流水对账状态：共 %d 条流水，已对账 %d 条，待对账 %d 条，对账率 %s%%",
                    total, reconciled, pending,
                    total > 0 ? BigDecimal.valueOf(reconciled).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP) : "0.00");
        } catch (Exception e) {
            return "查询对账状态失败：" + e.getMessage();
        }
    }

    /**
     * 利润归因分析（调用 AI 分析服务生成深度报告）
     */
    @Tool("对指定周期进行利润归因分析（生成深度分析报告，含原因诊断和优化建议，区别于queryProfitReport只返回数值）。参数：period 核算周期（格式 yyyyMM，如 202607）。当用户明确要求'分析/归因/诊断/为什么利润下降/给出建议'时使用此工具，而非queryProfitReport。")
    public String analyzeProfit(@P("核算周期，格式 yyyyMM，如 202607") String period) {
        try {
            String report = aiAnalysisService.analyzeProfit(period);
            return report == null || report.isBlank()
                    ? "周期 " + period + " 暂无利润数据可供分析，请先执行利润核算"
                    : report;
        } catch (Exception e) {
            return "利润归因分析失败：" + e.getMessage();
        }
    }

    /**
     * 查询导入批次状态（方案 B + D 新增）
     * <p>
     * 让 AI 顾问能回答"上次导入的批次清洗好了吗""最近导入情况怎么样"等问题。
     */
    @Tool("查询账单导入批次的处理状态。参数：limit 返回最近多少条批次（默认10，最大50）。返回每个批次的文件名、状态（IMPORTED/CLEANING/CLEANED/FAILED）、总数、成功数、失败数。当用户问'导入进度/清洗好了吗/批次状态'时使用此工具。")
    public String queryImportBatches(@P("返回批次数，默认10，最大50") Integer limit) {
        try {
            int queryLimit = limit == null || limit <= 0 ? 10 : Math.min(limit, 50);
            List<ImportBatch> batches = importBatchService.listAll();
            if (batches.isEmpty()) {
                return "暂无导入批次记录";
            }
            // 截取最近 N 条
            int from = Math.max(0, batches.size() - queryLimit);
            List<ImportBatch> recent = batches.subList(from, batches.size());
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("最近 %d 条导入批次状态：\n", recent.size()));
            for (ImportBatch b : recent) {
                sb.append(String.format("- 批次 %s｜文件 %s｜来源 %s｜状态 %s｜总数 %d｜成功 %d｜失败 %d\n",
                        b.getBatchNo(),
                        b.getFileName() == null ? "-" : b.getFileName(),
                        b.getSourceType(),
                        b.getStatus(),
                        b.getTotalCount() == null ? 0 : b.getTotalCount(),
                        b.getSuccessCount() == null ? 0 : b.getSuccessCount(),
                        b.getFailedCount() == null ? 0 : b.getFailedCount()));
            }
            return sb.toString();
        } catch (Exception e) {
            return "查询导入批次状态失败：" + e.getMessage();
        }
    }

    /**
     * 查询导入模板配置（方案 B + D 新增）
     * <p>
     * 让 AI 顾问能回答"我们有哪些导入模板""某个模板的字段映射是什么"等问题。
     */
    @Tool("查询账单导入模板配置列表。参数：sourceType 数据来源（PLATFORM/BANK，为空查全部）。返回每个模板的名称、平台、文件类型、字段映射、清洗规则。当用户问'有哪些模板/模板配置/字段映射'时使用此工具。")
    public String queryImportTemplates(@P("数据来源 PLATFORM 或 BANK，为空查全部") String sourceType) {
        try {
            List<ImportTemplate> templates;
            if (sourceType == null || sourceType.isBlank()) {
                templates = new java.util.ArrayList<>();
                templates.addAll(importTemplateService.listBySource(com.finance.platform.common.constant.BusinessConstants.SOURCE_PLATFORM));
                templates.addAll(importTemplateService.listBySource(com.finance.platform.common.constant.BusinessConstants.SOURCE_BANK));
            } else {
                templates = importTemplateService.listBySource(sourceType.toUpperCase());
            }
            if (templates.isEmpty()) {
                return "暂无导入模板配置";
            }
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("共 %d 个导入模板：\n", templates.size()));
            for (ImportTemplate t : templates) {
                sb.append(String.format("- ID=%d｜%s｜平台 %s｜类型 %s｜AI生成 %s\n  字段映射：%s\n  清洗规则：%s\n",
                        t.getId(),
                        t.getTemplateName(),
                        t.getPlatform() == null ? "通用" : t.getPlatform(),
                        t.getFileType(),
                        t.getAiGenerated() != null && t.getAiGenerated() == 1 ? "是" : "否",
                        t.getColumnMapping(),
                        t.getCleanRules() == null ? "无" : t.getCleanRules()));
            }
            return sb.toString();
        } catch (Exception e) {
            return "查询导入模板失败：" + e.getMessage();
        }
    }
}
