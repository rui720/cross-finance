package com.finance.platform.accounting.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.finance.platform.accounting.entity.ProfitReport;
import com.finance.platform.accounting.vo.ProfitDetailSummaryVO;

import java.util.List;
import java.util.Map;

/**
 * 利润引擎服务接口
 * <p>
 * 触发周期利润核算并对外提供利润报表分页查询能力。
 * 支持两种核算粒度：按 YYYYMM 月份周期、按自定义日期范围。
 */
public interface ProfitEngineService extends IService<ProfitReport> {

    /**
     * 触发指定月份周期的利润核算
     *
     * @param period 核算周期，如 202607
     */
    void calculate(String period);

    /**
     * 触发自定义日期范围的利润核算
     * <p>
     * period 字段存储为 "startDate~endDate" 形式（如 "2026-08-15~2026-09-15"）。
     *
     * @param startDate 起始日期 yyyy-MM-dd（含）
     * @param endDate   结束日期 yyyy-MM-dd（含）
     */
    void calculateByRange(String startDate, String endDate);

    /**
     * 分页查询利润报表
     * <p>
     * 支持三种过滤：
     * <ul>
     *   <li>按月份周期：period=202607</li>
     *   <li>按日期范围：startDate + endDate（覆盖 period 形如 "2026-08-15~2026-09-15" 的记录）</li>
     *   <li>无过滤：返回全部</li>
     * </ul>
     * 优先级：period > startDate/endDate > 全部。
     *
     * @param period    核算周期（可选）
     * @param startDate 起始日期（可选）
     * @param endDate   结束日期（可选）
     * @param page      页码
     * @param size      每页大小
     * @return 利润报表分页数据
     */
    Page<ProfitReport> getReport(String period, String startDate, String endDate, int page, int size);

    /**
     * 查询利润报表列表（不分页，用于导出 Excel）
     * <p>
     * 过滤逻辑与 {@link #getReport} 一致，但不分页，最多返回 10000 条防止内存溢出。
     *
     * @param period    核算周期（可选）
     * @param startDate 起始日期（可选）
     * @param endDate   结束日期（可选）
     * @return 利润报表列表
     */
    List<ProfitReport> getReportList(String period, String startDate, String endDate);

    /**
     * 带筛选条件的分页查询利润报表（先筛选再分页）
     * <p>
     * 在 {@link #getReport} 基础上增加平台/店铺/币种/对账状态筛选，
     * 保证每页返回的记录数等于分页大小，避免先分页再筛选导致的每页数量不定。
     *
     * @param period          核算周期（可选）
     * @param startDate       起始日期（可选）
     * @param endDate         结束日期（可选）
     * @param platform        平台（可选）
     * @param shopId          店铺（可选）
     * @param currency        币种（可选）
     * @param reconcileStatus 对账状态（可选）
     * @param page            页码
     * @param size            每页大小
     * @return 利润报表分页数据
     */
    Page<ProfitReport> getReportFiltered(String period, String startDate, String endDate,
                                          String platform, String shopId, String currency,
                                          Integer reconcileStatus, int page, int size);

    /**
     * 计算当前筛选条件下的利润明细汇总（用于表格底部合计行）
     * <p>
     * 汇总所有满足筛选条件的记录的金额合计，而非仅当前分页。
     *
     * @param period          核算周期（可选）
     * @param startDate       起始日期（可选）
     * @param endDate         结束日期（可选）
     * @param platform        平台（可选）
     * @param shopId          店铺（可选）
     * @param currency        币种（可选）
     * @param reconcileStatus 对账状态（可选）
     * @return 汇总视图
     */
    ProfitDetailSummaryVO getDetailSummary(String period, String startDate, String endDate,
                                            String platform, String shopId, String currency,
                                            Integer reconcileStatus);

    /**
     * 获取当前周期下所有不重复的平台/店铺/币种列表（用于前端筛选下拉选项）
     * <p>
     * 不受 platform/shopId/currency/reconcileStatus 筛选影响，始终返回当前周期（period 或日期范围）
     * 下的全量选项，避免筛选后下拉选项消失的问题。
     *
     * @param period    核算周期（可选）
     * @param startDate 起始日期（可选）
     * @param endDate   结束日期（可选）
     * @return Map：platforms / shops / currencies 三个不重复值列表
     */
    Map<String, List<String>> getFilterOptions(String period, String startDate, String endDate);
}
