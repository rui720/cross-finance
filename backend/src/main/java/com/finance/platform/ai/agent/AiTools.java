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
    private final BudgetControlService budgetControlService;
    private final PaymentFlowService paymentFlowService;
    private final CurrencyConvertUtils currencyConvertUtils;
    private final AiAnalysisService aiAnalysisService;
    private final SysAuditLogService sysAuditLogService;
    private final CostAllocationRuleMapper costAllocationRuleMapper;

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
                    .eq(RawOrder::getSource, "PLATFORM")
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
            var page = profitEngineService.getReport(period, 1, 200);
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
     * 查询预算预警
     */
    @Tool("查询当前已达到预警阈值的预算计划（即使用率超过预警线的预算）。无需参数。返回预警预算列表及使用率。")
    public String queryBudgetWarnings() {
        try {
            List<BudgetPlan> plans = budgetControlService.getWarningPlans();
            if (plans.isEmpty()) {
                return "当前没有预算达到预警阈值";
            }
            String detail = plans.stream()
                    .map(p -> {
                        BigDecimal usage = p.getTotalAmount().compareTo(BigDecimal.ZERO) > 0
                                ? p.getUsedAmount().multiply(BigDecimal.valueOf(100)).divide(p.getTotalAmount(), 2, RoundingMode.HALF_UP)
                                : BigDecimal.ZERO;
                        return String.format("%s（周期%s）：已用 %s/%s %s，使用率 %s%%，阈值 %s%%",
                                p.getPlanName(), p.getPeriod(),
                                p.getUsedAmount().setScale(2, RoundingMode.HALF_UP),
                                p.getTotalAmount().setScale(2, RoundingMode.HALF_UP),
                                p.getCurrency(), usage, p.getWarningThreshold());
                    })
                    .collect(Collectors.joining("; "));
            return String.format("当前有 %d 项预算达到预警阈值：%s", plans.size(), detail);
        } catch (Exception e) {
            return "查询预算预警失败：" + e.getMessage();
        }
    }

    /**
     * 查询付款申请
     */
    @Tool("查询付款申请单。参数：status 状态筛选（0草稿/1待审批/2已通过/3已驳回/4已付款，为空查全部）。返回申请单列表摘要。")
    public String queryPaymentApplies(@P("状态码：0草稿/1待审批/2已通过/3已驳回/4已付款；为空查全部") Integer status) {
        try {
            LambdaQueryWrapper<PaymentApply> wrapper = new LambdaQueryWrapper<PaymentApply>()
                    .orderByDesc(PaymentApply::getApplyTime);
            if (status != null) {
                wrapper.eq(PaymentApply::getStatus, status);
            }
            List<PaymentApply> list = paymentFlowService.list(wrapper);
            if (list.isEmpty()) {
                return status != null ? "未查询到该状态的付款申请" : "当前无付款申请记录";
            }
            String detail = list.stream()
                    .limit(20)
                    .map(p -> String.format("%s（%s，金额 %s %s，状态 %s，收款 %s）",
                            p.getApplyNo(), p.getApplyReason() == null ? "无事由" : p.getApplyReason(),
                            p.getAmount().setScale(2, RoundingMode.HALF_UP), p.getCurrency(),
                            statusText(p.getStatus()), p.getPayee()))
                    .collect(Collectors.joining("; "));
            return String.format("共 %d 条付款申请：%s", list.size(), detail);
        } catch (Exception e) {
            return "查询付款申请失败：" + e.getMessage();
        }
    }

    /**
     * 查询费用分摊规则
     */
    @Tool("查询当前系统配置的费用分摊规则列表。无需参数。返回规则名称、类型（按金额/按重量）、启用状态、描述等信息。")
    public String queryAllocationRules() {
        try {
            List<CostAllocationRule> rules = costAllocationRuleMapper.selectList(null);
            if (rules == null || rules.isEmpty()) {
                return "当前系统未配置费用分摊规则";
            }
            String detail = rules.stream()
                    .map(r -> String.format("%s（类型：%s，%s，%s）",
                            r.getRuleName(),
                            "AMOUNT".equals(r.getRuleType()) ? "按金额分摊" : "按重量分摊",
                            r.getEnabled() == 1 ? "已启用" : "已禁用",
                            r.getDescription() == null ? "无描述" : r.getDescription()))
                    .collect(Collectors.joining("; "));
            return String.format("共 %d 条分摊规则：%s", rules.size(), detail);
        } catch (Exception e) {
            return "查询分摊规则失败：" + e.getMessage();
        }
    }

    /**
     * 查询付款申请详情
     */
    @Tool("根据申请单号查询付款申请详情。参数：applyNo 申请单号（如 PAY-20260714-0001）。返回申请单的全部信息。")
    public String queryPaymentDetail(@P("申请单号，如 PAY-20260714-0001") String applyNo) {
        try {
            PaymentApply p = paymentFlowService.getOne(new LambdaQueryWrapper<PaymentApply>()
                    .eq(PaymentApply::getApplyNo, applyNo));
            if (p == null) {
                return "未找到申请单号 " + applyNo + " 的付款申请";
            }
            return String.format("付款申请详情：%s，收款方 %s，金额 %s %s，事由 %s，状态 %s，申请时间 %s，关联预算ID %s",
                    p.getApplyNo(), p.getPayee(),
                    p.getAmount().setScale(2, RoundingMode.HALF_UP), p.getCurrency(),
                    p.getApplyReason() == null ? "无" : p.getApplyReason(),
                    statusText(p.getStatus()),
                    p.getApplyTime() == null ? "无" : p.getApplyTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    p.getBudgetPlanId());
        } catch (Exception e) {
            return "查询付款详情失败：" + e.getMessage();
        }
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
                    .map(l -> String.format("[%s] %s %s（%s，耗时%sms，%s）",
                            l.getCreateTime() == null ? "" : l.getCreateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                            l.getUsername() == null ? "匿名" : l.getUsername(),
                            l.getOperation() == null ? "未知操作" : l.getOperation(),
                            l.getMethod() == null ? "" : l.getMethod(),
                            l.getCostTime() == null ? "0" : l.getCostTime(),
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
                    .eq(RawOrder::getSource, "BANK"));
            Long reconciled = rawOrderMapper.selectCount(new LambdaQueryWrapper<RawOrder>()
                    .eq(RawOrder::getSource, "BANK")
                    .isNotNull(RawOrder::getSettleTime));
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

    /** 付款状态码转中文 */
    private String statusText(Integer status) {
        if (status == null) return "未知";
        return switch (status) {
            case 0 -> "草稿";
            case 1 -> "待审批";
            case 2 -> "已通过";
            case 3 -> "已驳回";
            case 4 -> "已付款";
            default -> "未知(" + status + ")";
        };
    }
}
