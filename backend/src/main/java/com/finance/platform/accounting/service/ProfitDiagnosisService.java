package com.finance.platform.accounting.service;

import com.finance.platform.accounting.entity.ProfitReport;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 利润诊断服务接口
 * <p>
 * 把"一维数字"转化为"业务健康诊断信号"，覆盖四个底层逻辑：
 * <ul>
 *   <li>A. 结构值：平台/品类的利润结构占比，识别"增收不增利"</li>
 *   <li>B. 趋势与波动：环比同比变化，定位异常拐点</li>
 *   <li>C. 成本合理性：分摊规则诊断，识别高客单价订单被过度摊费</li>
 *   <li>D. 行动点：基于阈值与规则生成可执行预警与建议</li>
 * </ul>
 */
public interface ProfitDiagnosisService {

    /**
     * 完整诊断：一次性返回四个维度的诊断结果
     *
     * @param period 核算周期，如 202607
     * @return 诊断结果
     */
    DiagnosisResult diagnose(String period);

    /**
     * 诊断结果聚合
     *
     * @param period       核算周期
     * @param summary      整体摘要
     * @param structure    A 维度：结构占比
     * @param trend        B 维度：趋势与波动
     * @param cost         C 维度：成本合理性
     * @param actionPoints D 维度：行动点预警
     */
    record DiagnosisResult(
            String period,
            Summary summary,
            Structure structure,
            Trend trend,
            CostDiagnosis cost,
            List<ActionPoint> actionPoints
    ) {}

    /** 整体摘要 */
    record Summary(
            int orderCount,
            BigDecimal totalRevenue,
            BigDecimal totalCost,
            BigDecimal totalProfit,
            BigDecimal profitRate
    ) {}

    /**
     * A 维度：结构占比
     *
     * @param byPlatform  各平台结构数据（营收/成本/利润/占比/利润率）
     * @param healthFlags 平台健康标记：增收不增利、亏损平台等
     */
    record Structure(
            List<PlatformBreakdown> byPlatform,
            List<HealthFlag> healthFlags
    ) {}

    /**
     * 平台结构分解
     *
     * @param platform           平台名
     * @param revenue            营收（CNY）
     * @param cost               成本
     * @param profit             利润
     * @param revenueShare       营收占比（0-1）
     * @param profitShare        利润占比（0-1）
     * @param profitRate         利润率（0-1）
     * @param orderCount         订单数
     * @param revenueProfitGap   "增收不增利"差距 = revenueShare - profitShare（正值说明营收占比高于利润占比）
     */
    record PlatformBreakdown(
            String platform,
            BigDecimal revenue,
            BigDecimal cost,
            BigDecimal profit,
            BigDecimal revenueShare,
            BigDecimal profitShare,
            BigDecimal profitRate,
            int orderCount,
            BigDecimal revenueProfitGap
    ) {}

    /**
     * 健康预警标记
     *
     * @param level    级别：INFO / WARN / DANGER
     * @param platform 平台（可空，空表示整体）
     * @param type     类型：REVENUE_PROFIT_GAP / LOSS_PLATFORM / etc.
     * @param message  描述
     */
    record HealthFlag(
            String level,
            String platform,
            String type,
            String message
    ) {}

    /**
     * B 维度：趋势与波动
     *
     * @param currentPeriod     本期
     * @param previousPeriod   上期
     * @param previousProfitRate 上期利润率
     * @param currentProfitRate 本期利润率
     * @param profitRateDelta   利润率变化（本期 - 上期，百分点）
     * @param revenueDelta      营收环比（%）
     * @param costDelta         成本环比（%）
     * @param profitDelta       利润环比（%）
     * @param trendSignal       趋势信号：IMPROVING / DECLINING / STABLE / NO_HISTORY
     * @param explanation       信号解释
     */
    record Trend(
            String currentPeriod,
            String previousPeriod,
            BigDecimal previousProfitRate,
            BigDecimal currentProfitRate,
            BigDecimal profitRateDelta,
            BigDecimal revenueDelta,
            BigDecimal costDelta,
            BigDecimal profitDelta,
            String trendSignal,
            String explanation
    ) {}

    /**
     * C 维度：成本合理性诊断
     *
     * @param currentRuleName       当前分摊规则名
     * @param currentRuleType       当前分摊类型（AMOUNT/WEIGHT）
     * @param avgCostRate           平均成本率 = 总成本/总收入
     * @param maxCostRatePlatform   成本率最高的平台
     * @param maxCostRate           最高成本率
     * @param minCostRatePlatform   成本率最低的平台
     * @param minCostRate           最低成本率
     * @param highTicketOrders      被高估成本的高客单价订单数（按金额分摊时）
     * @param highTicketOverCost    高客单价订单被多摊的成本总额
     * @param suggestions           诊断建议（如"建议切换 WEIGHT 策略"）
     */
    record CostDiagnosis(
            String currentRuleName,
            String currentRuleType,
            BigDecimal avgCostRate,
            String maxCostRatePlatform,
            BigDecimal maxCostRate,
            String minCostRatePlatform,
            BigDecimal minCostRate,
            int highTicketOrders,
            BigDecimal highTicketOverCost,
            List<String> suggestions
    ) {}

    /**
     * D 维度：行动点
     *
     * @param level    级别：INFO / WARN / DANGER
     * @param category 类别：STRUCTURE / TREND / COST / ACTION
     * @param title    标题
     * @param detail   详情
     * @param suggestedAction 建议动作
     */
    record ActionPoint(
            String level,
            String category,
            String title,
            String detail,
            String suggestedAction
    ) {}
}
