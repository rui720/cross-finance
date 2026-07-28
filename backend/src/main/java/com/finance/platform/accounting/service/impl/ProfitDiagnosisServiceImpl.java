package com.finance.platform.accounting.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.platform.accounting.entity.ProfitReport;
import com.finance.platform.accounting.mapper.ProfitReportMapper;
import com.finance.platform.accounting.service.ProfitDiagnosisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 利润诊断服务实现
 * <p>
 * 把利润报表的"一维数字"转化为"业务健康诊断信号"：
 * <ul>
 *   <li>A. 结构占比：按平台拆分营收/成本/利润占比，识别"增收不增利"</li>
 *   <li>B. 环比趋势：与上一周期对比利润率/营收/成本变化</li>
 *   <li>C. 成本合理性：诊断分摊规则是否扭曲了真实盈亏</li>
 *   <li>D. 行动点：基于诊断结果生成可执行的预警与建议</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfitDiagnosisServiceImpl implements ProfitDiagnosisService {

    private final ProfitReportMapper profitReportMapper;

    /** 平台营收占比与利润占比差值超过此阈值则触发"增收不增利"预警（百分点） */
    private static final BigDecimal REVENUE_PROFIT_GAP_THRESHOLD = new BigDecimal("10");
    /** 利润率环比下降超过此阈值（百分点）触发趋势 DANGER */
    private static final BigDecimal TREND_DANGER_THRESHOLD = new BigDecimal("3");
    private static final BigDecimal TREND_WARN_THRESHOLD = new BigDecimal("1");
    /** 高客单价判定倍数：超过客单价均值 N 倍视为高客单价订单 */
    private static final BigDecimal HIGH_TICKET_MULTIPLIER = new BigDecimal("2");

    @Override
    public DiagnosisResult diagnose(String period) {
        log.info("[诊断] 开始利润诊断 period={}", period);

        List<ProfitReport> currentReports = profitReportMapper.selectList(
                new LambdaQueryWrapper<ProfitReport>().eq(ProfitReport::getPeriod, period));

        Summary summary = computeSummary(period, currentReports);
        Structure structure = computeStructure(currentReports, summary);
        Trend trend = computeTrend(period, summary);
        CostDiagnosis cost = computeCostDiagnosis(currentReports, summary);
        List<ActionPoint> actionPoints = generateActionPoints(structure, trend, cost, summary);

        return new DiagnosisResult(period, summary, structure, trend, cost, actionPoints);
    }

    // ==================== A. 整体摘要 ====================

    private Summary computeSummary(String period, List<ProfitReport> reports) {
        BigDecimal revenue = sum(reports, ProfitReport::getCnyAmount);
        BigDecimal cost = sum(reports, ProfitReport::getCostAmount);
        BigDecimal profit = sum(reports, ProfitReport::getProfitAmount);
        BigDecimal profitRate = revenue.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : profit.divide(revenue, 4, RoundingMode.HALF_UP);
        return new Summary(reports.size(), revenue, cost, profit, profitRate);
    }

    // ==================== B. 结构占比 ====================

    private Structure computeStructure(List<ProfitReport> reports, Summary summary) {
        // 按平台聚合
        Map<String, List<ProfitReport>> byPlatform = reports.stream()
                .collect(Collectors.groupingBy(r -> r.getPlatform() == null ? "未知" : r.getPlatform()));

        List<PlatformBreakdown> breakdowns = new ArrayList<>();
        for (Map.Entry<String, List<ProfitReport>> e : byPlatform.entrySet()) {
            String platform = e.getKey();
            List<ProfitReport> list = e.getValue();
            BigDecimal revenue = sum(list, ProfitReport::getCnyAmount);
            BigDecimal cost = sum(list, ProfitReport::getCostAmount);
            BigDecimal profit = sum(list, ProfitReport::getProfitAmount);
            BigDecimal revenueShare = summary.totalRevenue().compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO
                    : revenue.divide(summary.totalRevenue(), 4, RoundingMode.HALF_UP);
            BigDecimal profitShare = summary.totalProfit().compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO
                    : profit.divide(summary.totalProfit(), 4, RoundingMode.HALF_UP);
            BigDecimal profitRate = revenue.compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO
                    : profit.divide(revenue, 4, RoundingMode.HALF_UP);
            // 增收不增利差距 = 营收占比 - 利润占比（百分点）
            BigDecimal gap = revenueShare.subtract(profitShare)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP);
            breakdowns.add(new PlatformBreakdown(
                    platform, revenue, cost, profit,
                    revenueShare.setScale(4, RoundingMode.HALF_UP),
                    profitShare.setScale(4, RoundingMode.HALF_UP),
                    profitRate.setScale(4, RoundingMode.HALF_UP),
                    list.size(), gap));
        }
        // 按利润降序
        breakdowns.sort(Comparator.comparing(PlatformBreakdown::profit).reversed());

        // 健康标记
        List<HealthFlag> flags = new ArrayList<>();
        for (PlatformBreakdown b : breakdowns) {
            // 增收不增利
            if (b.revenueProfitGap().compareTo(REVENUE_PROFIT_GAP_THRESHOLD) > 0
                    && b.profit().compareTo(BigDecimal.ZERO) > 0) {
                flags.add(new HealthFlag("WARN", b.platform(), "REVENUE_PROFIT_GAP",
                        String.format("%s 营收占比 %s%% 但利润占比仅 %s%%，存在增收不增利",
                                b.platform(),
                                b.revenueShare().multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP),
                                b.profitShare().multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP))));
            }
            // 亏损平台
            if (b.profit().compareTo(BigDecimal.ZERO) < 0) {
                flags.add(new HealthFlag("DANGER", b.platform(), "LOSS_PLATFORM",
                        String.format("%s 当期亏损 %s CNY，需立即排查",
                                b.platform(),
                                b.profit().abs().setScale(2, RoundingMode.HALF_UP))));
            }
        }
        return new Structure(breakdowns, flags);
    }

    // ==================== C. 趋势与波动 ====================

    private Trend computeTrend(String period, Summary current) {
        String prevPeriod = previousPeriod(period);
        List<ProfitReport> prevReports = profitReportMapper.selectList(
                new LambdaQueryWrapper<ProfitReport>().eq(ProfitReport::getPeriod, prevPeriod));

        if (prevReports.isEmpty()) {
            return new Trend(period, prevPeriod, null, current.profitRate(),
                    null, null, null, null, "NO_HISTORY",
                    "无历史数据，无法计算环比，建议次月再查看趋势");
        }
        Summary prev = computeSummary(prevPeriod, prevReports);

        BigDecimal profitRateDelta = current.profitRate().subtract(prev.profitRate())
                .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal revenueDelta = pctDelta(prev.totalRevenue(), current.totalRevenue());
        BigDecimal costDelta = pctDelta(prev.totalCost(), current.totalCost());
        BigDecimal profitDelta = pctDelta(prev.totalProfit(), current.totalProfit());

        String signal;
        String explanation;
        if (profitRateDelta.compareTo(TREND_DANGER_THRESHOLD.negate()) < 0) {
            signal = "DECLINING";
            explanation = String.format("利润率较上期下降 %s 个百分点，需启动根因分析", profitRateDelta.abs());
        } else if (profitRateDelta.compareTo(TREND_WARN_THRESHOLD.negate()) < 0) {
            signal = "DECLINING";
            explanation = String.format("利润率较上期下降 %s 个百分点，关注成本变化", profitRateDelta.abs());
        } else if (profitRateDelta.compareTo(TREND_WARN_THRESHOLD) > 0) {
            signal = "IMPROVING";
            explanation = String.format("利润率较上期提升 %s 个百分点，业务向好", profitRateDelta);
        } else {
            signal = "STABLE";
            explanation = String.format("利润率较上期变化 %s 个百分点，整体平稳", profitRateDelta);
        }
        return new Trend(period, prevPeriod, prev.profitRate(), current.profitRate(),
                profitRateDelta, revenueDelta, costDelta, profitDelta, signal, explanation);
    }

    // ==================== D. 成本合理性 ====================

    private CostDiagnosis computeCostDiagnosis(List<ProfitReport> reports, Summary summary) {
        // 当前分摊规则（配置层已移除，核算引擎硬编码按金额占比分摊）
        String ruleName = "按金额占比分摊";
        String ruleType = "AMOUNT";

        // 平均成本率
        BigDecimal avgCostRate = summary.totalRevenue().compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : summary.totalCost().divide(summary.totalRevenue(), 4, RoundingMode.HALF_UP);

        // 按平台算成本率，找最高/最低
        Map<String, BigDecimal> platformRevenue = new HashMap<>();
        Map<String, BigDecimal> platformCost = new HashMap<>();
        for (ProfitReport r : reports) {
            String p = r.getPlatform() == null ? "未知" : r.getPlatform();
            platformRevenue.merge(p, r.getCnyAmount() == null ? BigDecimal.ZERO : r.getCnyAmount(), BigDecimal::add);
            platformCost.merge(p, r.getCostAmount() == null ? BigDecimal.ZERO : r.getCostAmount(), BigDecimal::add);
        }
        String maxPlatform = null, minPlatform = null;
        BigDecimal maxRate = BigDecimal.ZERO, minRate = null;
        for (Map.Entry<String, BigDecimal> e : platformRevenue.entrySet()) {
            BigDecimal rev = e.getValue();
            if (rev.compareTo(BigDecimal.ZERO) == 0) continue;
            BigDecimal cost = platformCost.getOrDefault(e.getKey(), BigDecimal.ZERO);
            BigDecimal rate = cost.divide(rev, 4, RoundingMode.HALF_UP);
            if (maxPlatform == null || rate.compareTo(maxRate) > 0) {
                maxPlatform = e.getKey();
                maxRate = rate;
            }
            if (minPlatform == null || minRate == null || rate.compareTo(minRate) < 0) {
                minPlatform = e.getKey();
                minRate = rate;
            }
        }
        if (minRate == null) minRate = BigDecimal.ZERO;

        // 高客单价订单被多摊成本诊断（仅在 AMOUNT 分摊策略下有意义）
        int highTicketCount = 0;
        BigDecimal highTicketOverCost = BigDecimal.ZERO;
        List<String> suggestions = new ArrayList<>();
        if ("AMOUNT".equals(ruleType) && !reports.isEmpty()) {
            // 客单价均值
            BigDecimal avgTicket = summary.totalRevenue().divide(
                    BigDecimal.valueOf(summary.orderCount()), 2, RoundingMode.HALF_UP);
            BigDecimal threshold = avgTicket.multiply(HIGH_TICKET_MULTIPLIER);
            // 平均成本率下的"应摊成本" vs 实际摊到的成本
            for (ProfitReport r : reports) {
                BigDecimal ticket = r.getCnyAmount() == null ? BigDecimal.ZERO : r.getCnyAmount();
                if (ticket.compareTo(threshold) >= 0) {
                    highTicketCount++;
                    BigDecimal expectedCost = ticket.multiply(avgCostRate);
                    BigDecimal actualCost = r.getCostAmount() == null ? BigDecimal.ZERO : r.getCostAmount();
                    if (actualCost.compareTo(expectedCost) > 0) {
                        highTicketOverCost = highTicketOverCost.add(actualCost.subtract(expectedCost));
                    }
                }
            }
            if (highTicketCount > 0 && highTicketOverCost.compareTo(BigDecimal.ZERO) > 0) {
                suggestions.add(String.format(
                        "当前按金额分摊，%d 笔高客单价订单被多摊成本约 %s CNY，可能掩盖低客单价订单的真实亏损，建议核查大额订单的成本归集",
                        highTicketCount, highTicketOverCost.setScale(2, RoundingMode.HALF_UP)));
            }
        }
        if (maxPlatform != null && minPlatform != null
                && maxRate.subtract(minRate).compareTo(new BigDecimal("0.3")) > 0) {
            suggestions.add(String.format(
                    "平台间成本率差异显著：%s 成本率 %s%% vs %s 成本率 %s%%，建议核查费用归集口径",
                    maxPlatform, maxRate.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP),
                    minPlatform, minRate.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP)));
        }
        if (suggestions.isEmpty()) {
            suggestions.add("成本分摊合理，未发现显著扭曲");
        }

        return new CostDiagnosis(ruleName, ruleType,
                avgCostRate.setScale(4, RoundingMode.HALF_UP),
                maxPlatform, maxRate.setScale(4, RoundingMode.HALF_UP),
                minPlatform, minRate.setScale(4, RoundingMode.HALF_UP),
                highTicketCount, highTicketOverCost.setScale(2, RoundingMode.HALF_UP),
                suggestions);
    }

    // ==================== E. 行动点生成 ====================

    private List<ActionPoint> generateActionPoints(Structure structure, Trend trend,
                                                    CostDiagnosis cost, Summary summary) {
        List<ActionPoint> points = new ArrayList<>();

        // 1. 亏损平台 → 立即排查
        for (HealthFlag flag : structure.healthFlags()) {
            if ("LOSS_PLATFORM".equals(flag.type())) {
                points.add(new ActionPoint("DANGER", "STRUCTURE",
                        "立即排查亏损平台：" + flag.platform(),
                        flag.message(),
                        "核查该平台的成本归集与费用分摊，定位亏损订单"));
            } else if ("REVENUE_PROFIT_GAP".equals(flag.type())) {
                points.add(new ActionPoint("WARN", "STRUCTURE",
                        "关注增收不增利平台：" + flag.platform(),
                        flag.message(),
                        "排查该平台广告费、物流费是否异常上升"));
            }
        }

        // 2. 趋势预警
        if ("DECLINING".equals(trend.trendSignal())) {
            String level = trend.profitRateDelta() != null
                    && trend.profitRateDelta().abs().compareTo(TREND_DANGER_THRESHOLD) >= 0
                    ? "DANGER" : "WARN";
            points.add(new ActionPoint(level, "TREND",
                    "利润率环比下滑",
                    trend.explanation(),
                    "对比上期成本结构变化，定位上涨项（物流/广告/平台费）"));
        } else if ("IMPROVING".equals(trend.trendSignal())) {
            points.add(new ActionPoint("INFO", "TREND",
                    "利润率环比提升",
                    trend.explanation(),
                    "总结本期正向因素，复制到其他平台或周期"));
        }

        // 3. 成本分摊建议
        for (String suggestion : cost.suggestions()) {
            if (suggestion.contains("多摊成本") || suggestion.contains("差异显著")) {
                points.add(new ActionPoint("WARN", "COST",
                        "成本分摊诊断",
                        suggestion,
                        "核查相关订单的成本归集与费用分摊口径"));
            }
        }

        // 4. 整体利润率预警
        if (summary.profitRate().compareTo(new BigDecimal("0.05")) < 0
                && summary.profitRate().compareTo(BigDecimal.ZERO) >= 0) {
            points.add(new ActionPoint("WARN", "ACTION",
                    "整体利润率偏低",
                    String.format("当前整体利润率仅 %s%%，低于 5%% 健康线",
                            summary.profitRate().multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP)),
                    "考虑优化产品结构或与供应商重新议价"));
        } else if (summary.profitRate().compareTo(BigDecimal.ZERO) < 0) {
            points.add(new ActionPoint("DANGER", "ACTION",
                    "整体处于亏损状态",
                    String.format("当期亏损 %s CNY",
                            summary.totalProfit().abs().setScale(2, RoundingMode.HALF_UP)),
                    "立即暂停非必要支出，启动全面成本审视"));
        }

        if (points.isEmpty()) {
            points.add(new ActionPoint("INFO", "ACTION",
                    "业务运行健康",
                    "未发现显著预警信号",
                    "保持当前运营策略，持续监控关键指标"));
        }
        return points;
    }

    // ==================== 工具方法 ====================

    private BigDecimal sum(List<ProfitReport> list,
                           java.util.function.Function<ProfitReport, BigDecimal> getter) {
        return list.stream()
                .map(getter)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal pctDelta(BigDecimal prev, BigDecimal curr) {
        if (prev == null || prev.compareTo(BigDecimal.ZERO) == 0) return null;
        return curr.subtract(prev)
                .divide(prev, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** 上一周期分隔符（与核算引擎保持一致） */
    private static final String RANGE_SEPARATOR = "~";

    /**
     * 计算上一周期：
     * <ul>
     *   <li>月份模式（yyyyMM，6 位）→ 上个月</li>
     *   <li>日期范围模式（"2026-08-15~2026-09-15"）→ 整体向前平移同等天数</li>
     * </ul>
     */
    private String previousPeriod(String period) {
        if (period == null || period.isBlank()) return "";
        // 日期范围模式：按同等天数向前平移
        if (period.contains(RANGE_SEPARATOR)) {
            String[] parts = period.split(RANGE_SEPARATOR);
            if (parts.length != 2) return "";
            try {
                LocalDate start = LocalDate.parse(parts[0].trim());
                LocalDate end = LocalDate.parse(parts[1].trim());
                long days = ChronoUnit.DAYS.between(start, end) + 1;
                LocalDate prevStart = start.minusDays(days);
                LocalDate prevEnd = start.minusDays(1);
                return prevStart.toString() + RANGE_SEPARATOR + prevEnd.toString();
            } catch (Exception e) {
                return "";
            }
        }
        // 月份模式
        if (period.length() != 6) return "";
        try {
            int year = Integer.parseInt(period.substring(0, 4));
            int month = Integer.parseInt(period.substring(4, 6));
            if (month == 1) {
                return String.format("%04d%02d", year - 1, 12);
            }
            return String.format("%04d%02d", year, month - 1);
        } catch (NumberFormatException e) {
            return "";
        }
    }
}
