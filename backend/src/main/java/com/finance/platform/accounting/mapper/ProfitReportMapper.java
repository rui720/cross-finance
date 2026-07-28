package com.finance.platform.accounting.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.finance.platform.accounting.entity.ProfitReport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 利润报表数据访问层
 * <p>
 * 除 BaseMapper 通用能力外，提供面向驾驶舱与多维度分析的聚合查询：
 * <ul>
 *   <li>{@link #selectMonthlyTrend}：月度趋势（仅 YYYYMM 周期）</li>
 *   <li>{@link #selectCurrencySummary}：按币种汇总</li>
 *   <li>{@link #selectShopSummary}：按店铺汇总（含订单数/营收/成本/利润/到账）</li>
 *   <li>{@link #selectCostStructure}：成本结构拆分汇总（平台费/公共分摊/直接成本）</li>
 *   <li>{@link #selectReconcileSummary}：对账汇总（已对账/未到账/差异笔数及金额）</li>
 *   <li>{@link #selectLossOrders}：亏损订单 Top N</li>
 *   <li>{@link #selectAggregateBy}：按维度（platform/shop/currency）分组聚合</li>
 * </ul>
 * 所有聚合方法均按 period 精确匹配（兼容 YYYYMM 与 "startDate~endDate" 两种格式）。
 */
@Mapper
public interface ProfitReportMapper extends BaseMapper<ProfitReport> {

    /**
     * 按核算周期分组的月度汇总（用于驾驶舱趋势图）
     * <p>
     * 仅取 YYYYMM 格式的周期（形如 "2026-08-15~2026-09-15" 的范围周期不参与趋势统计），
     * 按 period 升序返回，前端据此绘制近 N 个月的收入/成本/利润折线。
     *
     * @param recentPeriods 最近 N 个月的 period 列表（YYYYMM），为空则返回空列表
     * @return 每月一行：period, total_revenue, total_cost, total_profit
     */
    @Select({
        "<script>",
        "SELECT period,",
        "       COALESCE(SUM(cny_amount), 0)     AS total_revenue,",
        "       COALESCE(SUM(cost_amount), 0)    AS total_cost,",
        "       COALESCE(SUM(profit_amount), 0)  AS total_profit",
        "FROM profit_report",
        "WHERE deleted = 0",
        "  AND period IN",
        "  <foreach item='p' collection='recentPeriods' open='(' separator=',' close=')'>#{p}</foreach>",
        "  AND LENGTH(period) = 6",
        "GROUP BY period",
        "ORDER BY period ASC",
        "</script>"
    })
    List<Map<String, Object>> selectMonthlyTrend(@Param("recentPeriods") List<String> recentPeriods);

    /**
     * 按币种分组的当期汇总（用于驾驶舱币种收入分布柱状图）
     * <p>
     * 同时返回原币金额合计与折算人民币金额合计，前端可按 CNY 金额排序绘制柱状图。
     *
     * @param period 核算周期 YYYYMM
     * @return 每币种一行：currency, original_amount, cny_amount
     */
    @Select({
        "SELECT currency,",
        "       COALESCE(SUM(original_amount), 0) AS original_amount,",
        "       COALESCE(SUM(cny_amount), 0)      AS cny_amount",
        "FROM profit_report",
        "WHERE deleted = 0 AND period = #{period}",
        "GROUP BY currency",
        "ORDER BY cny_amount DESC"
    })
    List<Map<String, Object>> selectCurrencySummary(@Param("period") String period);

    /**
     * 按店铺汇总（用于店铺维度分析）。
     * <p>
     * shop_id 为空时归入 "未指定" 分组。返回每店铺的订单数、营收、成本、利润、平均利润率、实际到账金额。
     *
     * @param period 核算周期
     * @return 每店铺一行：shop_id, order_count, total_revenue, total_cost, total_profit, profit_rate, actual_received
     */
    @Select({
        "SELECT COALESCE(NULLIF(shop_id, ''), '未指定') AS shop_id,",
        "       COUNT(*)                                AS order_count,",
        "       COALESCE(SUM(cny_amount), 0)             AS total_revenue,",
        "       COALESCE(SUM(cost_amount), 0)            AS total_cost,",
        "       COALESCE(SUM(profit_amount), 0)          AS total_profit,",
        "       COALESCE(SUM(actual_received_amount), 0) AS actual_received",
        "FROM profit_report",
        "WHERE deleted = 0 AND period = #{period}",
        "GROUP BY COALESCE(NULLIF(shop_id, ''), '未指定')",
        "ORDER BY total_profit DESC"
    })
    List<Map<String, Object>> selectShopSummary(@Param("period") String period);

    /**
     * 成本结构汇总：返回当期所有订单的成本拆分合计。
     * <p>
     * 用于在报表页头部展示"钱花哪去了"：平台费、公共分摊、直接成本三段占比。
     *
     * @param period 核算周期
     * @return 单行：total_fee, total_shared, total_direct, total_cost
     */
    @Select({
        "SELECT COALESCE(SUM(fee_cost), 0)    AS total_fee,",
        "       COALESCE(SUM(shared_cost), 0) AS total_shared,",
        "       COALESCE(SUM(direct_cost), 0) AS total_direct,",
        "       COALESCE(SUM(cost_amount), 0) AS total_cost",
        "FROM profit_report",
        "WHERE deleted = 0 AND period = #{period}"
    })
    Map<String, Object> selectCostStructure(@Param("period") String period);

    /**
     * 对账汇总：统计当期对账状态分布与实际到账金额。
     * <p>
     * 用于"账面利润 vs 实际到账利润"对比卡片：
     * <ul>
     *   <li>matched_count / matched_amount：已完成对账的订单数与到账金额（reconcile_status=1）</li>
     *   <li>unreceived_count：未到账订单数（reconcile_status=3）</li>
     *   <li>diff_count：对账失败订单数（reconcile_status=2）</li>
     *   <li>total_actual_received：实际到账金额合计</li>
     *   <li>total_book_profit：账面利润合计</li>
     * </ul>
     *
     * @param period 核算周期
     * @return 单行汇总
     */
    @Select({
        "SELECT",
        "  COALESCE(SUM(CASE WHEN reconcile_status = 1 THEN 1 ELSE 0 END), 0) AS matched_count,",
        "  COALESCE(SUM(CASE WHEN reconcile_status = 1 THEN actual_received_amount ELSE 0 END), 0) AS matched_amount,",
        "  COALESCE(SUM(CASE WHEN reconcile_status = 3 THEN 1 ELSE 0 END), 0) AS unreceived_count,",
        "  COALESCE(SUM(CASE WHEN reconcile_status = 2 THEN 1 ELSE 0 END), 0) AS diff_count,",
        "  COALESCE(SUM(actual_received_amount), 0) AS total_actual_received,",
        "  COALESCE(SUM(profit_amount), 0)          AS total_book_profit,",
        "  COALESCE(SUM(cny_amount), 0)             AS total_revenue",
        "FROM profit_report",
        "WHERE deleted = 0 AND period = #{period}"
    })
    Map<String, Object> selectReconcileSummary(@Param("period") String period);

    /**
     * 亏损订单 Top N（按利润升序，即亏损最多的排在最前）。
     * <p>
     * 用于亏损订单清单卡片，帮助财务快速定位异常订单。仅返回利润为负的订单。
     *
     * @param period 核算周期
     * @param limit  返回条数
     * @return 每行：order_no, platform, shop_id, cny_amount, cost_amount, profit_amount, profit_rate, reconcile_status
     */
    @Select({
        "SELECT order_no, platform, shop_id, currency, cny_amount, cost_amount,",
        "       profit_amount, profit_rate, reconcile_status",
        "FROM profit_report",
        "WHERE deleted = 0 AND period = #{period} AND profit_amount < 0",
        "ORDER BY profit_amount ASC",
        "LIMIT #{limit}"
    })
    List<Map<String, Object>> selectLossOrders(@Param("period") String period, @Param("limit") int limit);

    /**
     * 按指定维度字段分组聚合（用于多维聚合切换）。
     * <p>
     * dimension 仅允许 platform / shop_id / currency 三个值（由 Controller 层做白名单校验），
     * 通过 ${dim} 拼接到 SQL 中（已被白名单过滤，无注入风险）。
     *
     * @param period    核算周期
     * @param dimension 维度字段名：platform / shop_id / currency
     * @return 每行：dim_value, order_count, total_revenue, total_cost, total_profit, profit_rate, actual_received
     */
    @Select({
        "<script>",
        "SELECT COALESCE(NULLIF(${dim}, ''), '未指定') AS dim_value,",
        "       COUNT(*)                                AS order_count,",
        "       COALESCE(SUM(cny_amount), 0)             AS total_revenue,",
        "       COALESCE(SUM(cost_amount), 0)            AS total_cost,",
        "       COALESCE(SUM(profit_amount), 0)          AS total_profit,",
        "       COALESCE(SUM(actual_received_amount), 0) AS actual_received",
        "FROM profit_report",
        "WHERE deleted = 0 AND period = #{period}",
        "GROUP BY COALESCE(NULLIF(${dim}, ''), '未指定')",
        "ORDER BY total_profit DESC",
        "</script>"
    })
    List<Map<String, Object>> selectAggregateBy(@Param("period") String period, @Param("dim") String dimension);
}
