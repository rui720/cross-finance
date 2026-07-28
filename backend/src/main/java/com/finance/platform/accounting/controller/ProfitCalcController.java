package com.finance.platform.accounting.controller;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.write.metadata.style.WriteCellStyle;
import com.alibaba.excel.write.metadata.style.WriteFont;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finance.platform.accounting.entity.ProfitReport;
import com.finance.platform.accounting.mapper.ProfitReportMapper;
import com.finance.platform.accounting.service.DataIntegrityCheckService;
import com.finance.platform.accounting.service.ProfitDiagnosisService;
import com.finance.platform.accounting.service.ProfitEngineService;
import com.finance.platform.accounting.vo.ProfitDetailVO;
import com.finance.platform.accounting.vo.ProfitDetailSummaryVO;
import com.finance.platform.common.core.Result;
import com.finance.platform.data.service.BankReconcileService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 利润计算触发接口
 * <p>
 * 对外提供周期利润核算触发与利润报表分页查询能力。
 * 权限：触发核算仅 ADMIN/FINANCE；查询报表对 ADMIN/FINANCE/OPERATOR 开放（运营只读）。
 * <p>
 * 支持两种核算粒度：
 * <ul>
 *   <li>按 YYYYMM 月份周期（向后兼容旧接口）：/calculate?period=202607</li>
 *   <li>按自定义日期范围（新）：/calculate-by-range?startDate=2026-08-15&endDate=2026-09-15</li>
 * </ul>
 * 日期范围模式下会先调用 {@link #checkData} 做数据完整性检查并返回缺失明细。
 */
@Slf4j
@RestController
@RequestMapping("/accounting/profit")
@RequiredArgsConstructor
public class ProfitCalcController {

    private final ProfitEngineService profitEngineService;
    private final ProfitDiagnosisService profitDiagnosisService;
    private final DataIntegrityCheckService dataIntegrityCheckService;
    private final ProfitReportMapper profitReportMapper;
    private final BankReconcileService bankReconcileService;

    /**
     * 触发指定月份周期的利润核算（向后兼容旧接口）
     *
     * @param period 核算周期，如 202607
     * @param force  是否跳过完整性检查强制核算（默认 false）。前端确认缺失提示后仍要继续核算时传 true
     */
    @PostMapping("/calculate")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public Result<DataIntegrityCheckService.CheckResult> calculate(@RequestParam String period,
                                                                    @RequestParam(defaultValue = "false") boolean force) {
        log.info("[利润] 触发核算 period={}, force={}", period, force);
        // 后端强制完整性检查：将 YYYYMM 转为当月首尾日期，复用 RANGE 检查
        if (!force) {
            String[] range = periodToRange(period);
            if (range != null) {
                DataIntegrityCheckService.CheckResult checkResult = dataIntegrityCheckService.check(range[0], range[1]);
                if (checkResult.blocking()) {
                    log.warn("[利润] 周期 {} 完整性检查未通过，阻断核算：账单缺失 {} 段，汇率缺失 {} 段，币种汇率缺失 {} 项",
                            period,
                            checkResult.missingRanges().stream().filter(r -> "BILL".equals(r.type())).count(),
                            checkResult.missingRanges().stream().filter(r -> "EXCHANGE_RATE".equals(r.type())).count(),
                            checkResult.currencyRateMissing().size());
                    return Result.error("数据完整性检查未通过，已阻断核算（force=false）", checkResult);
                }
            }
        }
        profitEngineService.calculate(period);
        return Result.success();
    }

    /**
     * 触发自定义日期范围的利润核算（新）
     * <p>
     * period 字段存储为 "startDate~endDate" 形式（如 "2026-08-15~2026-09-15"），
     * 核算逻辑按 order_time 在 [startDate, endDate+1day) 范围过滤订单，
     * 额外费用按 cost_date 落在该范围过滤。
     *
     * @param startDate 起始日期 yyyy-MM-dd（含）
     * @param endDate   结束日期 yyyy-MM-dd（含）
     * @param force     是否跳过完整性检查强制核算（默认 false）。前端确认缺失提示后仍要继续核算时传 true
     */
    @PostMapping("/calculate-by-range")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public Result<DataIntegrityCheckService.CheckResult> calculateByRange(@RequestParam String startDate,
                                                                            @RequestParam String endDate,
                                                                            @RequestParam(defaultValue = "false") boolean force) {
        log.info("[利润] 触发核算 range={}~{}, force={}", startDate, endDate, force);
        // 后端强制完整性检查，防止绕过前端直接调用接口
        if (!force) {
            DataIntegrityCheckService.CheckResult checkResult = dataIntegrityCheckService.check(startDate, endDate);
            if (checkResult.blocking()) {
                log.warn("[利润] 范围 {}~{} 完整性检查未通过，阻断核算：账单缺失 {} 段，汇率缺失 {} 段，币种汇率缺失 {} 项",
                        startDate, endDate,
                        checkResult.missingRanges().stream().filter(r -> "BILL".equals(r.type())).count(),
                        checkResult.missingRanges().stream().filter(r -> "EXCHANGE_RATE".equals(r.type())).count(),
                        checkResult.currencyRateMissing().size());
                return Result.error("数据完整性检查未通过，已阻断核算（force=false）", checkResult);
            }
        }
        profitEngineService.calculateByRange(startDate, endDate);
        return Result.success();
    }

    /** 将 YYYYMM 周期转换为 [当月第一天, 当月最后一天] */
    private String[] periodToRange(String period) {
        if (period == null || period.length() != 6) return null;
        try {
            int year = Integer.parseInt(period.substring(0, 4));
            int month = Integer.parseInt(period.substring(4, 6));
            LocalDate start = LocalDate.of(year, month, 1);
            LocalDate end = start.plusMonths(1).minusDays(1);
            return new String[]{start.toString(), end.toString()};
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 数据完整性检查（核算前调用）
     * <p>
     * 按日期范围逐天扫描账单/银行流水/汇率/额外费用的覆盖情况，
     * 返回结构化的缺失明细，前端据此弹出详细提示对话框。
     *
     * @param startDate 起始日期 yyyy-MM-dd（含）
     * @param endDate   结束日期 yyyy-MM-dd（含）
     */
    @GetMapping("/check-data")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','OPERATOR')")
    public Result<DataIntegrityCheckService.CheckResult> checkData(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        return Result.success(dataIntegrityCheckService.check(startDate, endDate));
    }

    /**
     * 分页查询利润报表
     * <p>
     * 支持三种过滤：
     * <ul>
     *   <li>按月份周期：period=202607</li>
     *   <li>按日期范围：startDate + endDate（覆盖 period 字段形如 "2026-08-15~2026-09-15" 的记录）</li>
     *   <li>无过滤：返回全部</li>
     * </ul>
     *
     * @param period    核算周期（可选）
     * @param startDate 起始日期（可选，yyyy-MM-dd）
     * @param endDate   结束日期（可选，yyyy-MM-dd）
     * @param page      页码
     * @param size      每页大小
     */
    @GetMapping("/report")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','OPERATOR')")
    public Result<Page<ProfitReport>> report(@RequestParam(required = false) String period,
                                              @RequestParam(required = false) String startDate,
                                              @RequestParam(required = false) String endDate,
                                              @RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "10") int size) {
        return Result.success(profitEngineService.getReport(period, startDate, endDate, page, size));
    }

    /**
     * 分页查询利润明细（聚合视图）
     * <p>
     * 以账单为主体，按订单号关联银行流水、中转费与利润核算结果，一行展示完整利润明细。
     * 与对账模式口径一致，集中展示账单金额、平台手续费、中转费、银行到账、对账差值等关键金额列，
     * 同时附带公共分摊、直接成本、总成本、利润、利润率等核算字段。
     * <p>
     * 支持平台/店铺/币种/对账状态筛选，<b>先筛选再分页</b>，保证每页记录数等于分页大小。
     * 同时返回当前筛选条件下的汇总数据（用于表格底部合计行，覆盖全部筛选结果而非仅当前页）。
     *
     * @param period          核算周期（可选）
     * @param startDate       起始日期（可选，yyyy-MM-dd）
     * @param endDate         结束日期（可选，yyyy-MM-dd）
     * @param platform        平台（可选）
     * @param shopId          店铺（可选）
     * @param currency        币种（可选）
     * @param reconcileStatus 对账状态（可选，0/1/2/3/4）
     * @param page            页码
     * @param size            每页大小
     */
    @GetMapping("/detail")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','OPERATOR')")
    public Result<Map<String, Object>> detail(@RequestParam(required = false) String period,
                                                @RequestParam(required = false) String startDate,
                                                @RequestParam(required = false) String endDate,
                                                @RequestParam(required = false) String platform,
                                                @RequestParam(required = false) String shopId,
                                                @RequestParam(required = false) String currency,
                                                @RequestParam(required = false) Integer reconcileStatus,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "10") int size) {
        Page<ProfitReport> reportPage = profitEngineService.getReportFiltered(
                period, startDate, endDate, platform, shopId, currency, reconcileStatus, page, size);
        Page<ProfitDetailVO> voPage = new Page<>(reportPage.getCurrent(), reportPage.getSize(), reportPage.getTotal());
        List<ProfitDetailVO> records = reportPage.getRecords().stream()
                .map(bankReconcileService::buildProfitDetail)
                .collect(java.util.stream.Collectors.toList());
        voPage.setRecords(records);
        // 当前筛选条件下的全量汇总（用于表格底部合计行）
        ProfitDetailSummaryVO summary = profitEngineService.getDetailSummary(
                period, startDate, endDate, platform, shopId, currency, reconcileStatus);
        // 当前周期下的全量筛选选项（不受筛选条件影响，避免筛选后下拉选项消失）
        Map<String, List<String>> options = profitEngineService.getFilterOptions(period, startDate, endDate);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("page", voPage);
        result.put("summary", summary);
        result.put("options", options);
        return Result.success(result);
    }

    /**
     * 利润诊断：返回四个维度的业务健康诊断信号
     * <ul>
     *   <li>A. 结构占比：各平台营收/成本/利润占比，识别增收不增利</li>
     *   <li>B. 趋势与波动：环比变化，定位异常拐点</li>
     *   <li>C. 成本合理性：分摊规则诊断</li>
     *   <li>D. 行动点：可执行的预警与建议</li>
     * </ul>
     *
     * @param period 核算周期，如 202607
     */
    @GetMapping("/diagnosis")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','OPERATOR')")
    public Result<ProfitDiagnosisService.DiagnosisResult> diagnosis(@RequestParam String period) {
        log.info("[利润] 触发利润诊断 period={}", period);
        return Result.success(profitDiagnosisService.diagnose(period));
    }

    /**
     * 经营驾驶舱聚合数据：一次性返回当期摘要、平台结构、近 12 个月趋势、币种分布
     * <p>
     * 数据来源均为已核算入库的 profit_report，不触发重新核算。
     * 当期无数据时返回零值摘要与空数组，前端据此显示「暂无数据，请先核算」。
     *
     * @param period 核算周期 YYYYMM（前端默认传上个月）
     */
    @GetMapping("/dashboard")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','OPERATOR')")
    public Result<Map<String, Object>> dashboard(@RequestParam String period) {
        log.info("[驾驶舱] 加载聚合数据 period={}", period);

        // 1. 复用诊断服务的整体摘要与平台结构（含环比计算）
        ProfitDiagnosisService.DiagnosisResult diagnosis = profitDiagnosisService.diagnose(period);
        ProfitDiagnosisService.Summary summary = diagnosis.summary();
        List<ProfitDiagnosisService.PlatformBreakdown> byPlatform = diagnosis.structure() == null
                ? List.of() : diagnosis.structure().byPlatform();

        // 2. 近 12 个月趋势（包含当期）：构造 period 列表后聚合查询
        List<String> recentPeriods = buildRecentMonthlyPeriods(period, 12);
        List<Map<String, Object>> monthlyRows = recentPeriods.isEmpty()
                ? List.of() : profitReportMapper.selectMonthlyTrend(recentPeriods);
        // 补齐缺失月份（无核算数据的月份填 0），保证折线图横轴连续
        List<Map<String, Object>> monthlyTrend = fillMissingMonths(recentPeriods, monthlyRows);

        // 3. 当期按币种汇总
        List<Map<String, Object>> byCurrency = profitReportMapper.selectCurrencySummary(period);

        // 4. 组装返回
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", period);
        result.put("summary", Map.of(
                "orderCount", summary.orderCount(),
                "totalRevenue", summary.totalRevenue(),
                "totalCost", summary.totalCost(),
                "totalProfit", summary.totalProfit(),
                "profitRate", summary.profitRate()
        ));
        result.put("byPlatform", byPlatform);
        result.put("monthlyTrend", monthlyTrend);
        result.put("byCurrency", byCurrency);
        return Result.success(result);
    }

    /**
     * 构造以 period 为终点、向前回溯 months-1 个月的 YYYYMM 列表（共 months 个，升序）
     * 例：period=202607, months=12 → [202508, 202509, ..., 202607]
     */
    private List<String> buildRecentMonthlyPeriods(String period, int months) {
        if (period == null || period.length() != 6) return List.of();
        try {
            int year = Integer.parseInt(period.substring(0, 4));
            int month = Integer.parseInt(period.substring(4, 6));
            List<String> list = new ArrayList<>(months);
            LocalDate cursor = LocalDate.of(year, month, 1);
            for (int i = months - 1; i >= 0; i--) {
                LocalDate d = cursor.minusMonths(i);
                list.add(String.format("%04d%02d", d.getYear(), d.getMonthValue()));
            }
            return list;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 将查询结果按 recentPeriods 顺序补齐缺失月份（无数据的月份填 0），保证折线图横轴连续
     */
    private List<Map<String, Object>> fillMissingMonths(List<String> recentPeriods,
                                                         List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> byPeriod = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object p = row.get("period");
            if (p != null) byPeriod.put(p.toString(), row);
        }
        List<Map<String, Object>> result = new ArrayList<>(recentPeriods.size());
        for (String p : recentPeriods) {
            Map<String, Object> row = byPeriod.get(p);
            if (row != null) {
                result.add(row);
            } else {
                Map<String, Object> zero = new LinkedHashMap<>();
                zero.put("period", p);
                zero.put("total_revenue", BigDecimal.ZERO);
                zero.put("total_cost", BigDecimal.ZERO);
                zero.put("total_profit", BigDecimal.ZERO);
                result.add(zero);
            }
        }
        return result;
    }

    /**
     * 导出利润报表为 Excel
     * <p>
     * 强制要求至少传 period 或 startDate+endDate 其一，避免一次性导出全平台所有利润数据。
     * 最多导出 10000 条，使用 EasyExcel 流式写入避免内存溢出。
     * 导出列（增强版）：账单时间、订单号、平台、店铺、币种、原币金额、CNY 金额、
     *   平台费、公共分摊、直接成本、总成本、利润金额、利润率、对账状态、实际到账金额。
     *
     * @param period    核算周期（与 startDate+endDate 二选一）
     * @param startDate 起始日期（yyyy-MM-dd，与 period 二选一）
     * @param endDate   结束日期（yyyy-MM-dd，与 period 二选一）
     */
    @GetMapping("/export")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','OPERATOR')")
    public void exportReport(@RequestParam(required = false) String period,
                              @RequestParam(required = false) String startDate,
                              @RequestParam(required = false) String endDate,
                              HttpServletResponse response) throws Exception {
        // 强制过滤条件：避免无过滤导出全部数据
        boolean hasPeriod = period != null && !period.isBlank();
        boolean hasRange = startDate != null && !startDate.isBlank()
                && endDate != null && !endDate.isBlank();
        if (!hasPeriod && !hasRange) {
            response.setStatus(400);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":400,\"msg\":\"导出必须指定 period 或 startDate+endDate 过滤条件，避免一次性导出全平台数据\"}");
            return;
        }
        log.info("[利润] 导出报表 period={}, range={}~{}", period, startDate, endDate);
        List<ProfitReport> list = profitEngineService.getReportList(period, startDate, endDate);
        // 构造导出文件名
        String scope = period != null ? period
                : (startDate != null && endDate != null ? startDate + "_" + endDate : "all");
        String fileName = "利润报表_" + scope + ".xlsx";
        String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replaceAll("\\+", "%20");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encodedFileName);

        // 表头数据：二维数组，每个子数组代表一列
        List<List<String>> head = new ArrayList<>();
        head.add(List.of("账单时间"));
        head.add(List.of("订单号"));
        head.add(List.of("平台"));
        head.add(List.of("店铺"));
        head.add(List.of("币种"));
        head.add(List.of("原币金额"));
        head.add(List.of("CNY 金额"));
        head.add(List.of("平台费"));
        head.add(List.of("公共分摊"));
        head.add(List.of("直接成本"));
        head.add(List.of("总成本"));
        head.add(List.of("利润金额"));
        head.add(List.of("利润率"));
        head.add(List.of("对账状态"));
        head.add(List.of("实际到账金额"));

        // 数据行
        List<List<Object>> data = new ArrayList<>();
        for (ProfitReport r : list) {
            List<Object> row = new ArrayList<>();
            row.add(r.getOrderTime());
            row.add(r.getOrderNo());
            row.add(r.getPlatform());
            row.add(r.getShopId());
            row.add(r.getCurrency());
            row.add(r.getOriginalAmount());
            row.add(r.getCnyAmount());
            row.add(r.getFeeCost());
            row.add(r.getSharedCost());
            row.add(r.getDirectCost());
            row.add(r.getCostAmount());
            row.add(r.getProfitAmount());
            row.add(r.getProfitRate());
            row.add(reconcileStatusName(r.getReconcileStatus()));
            row.add(r.getActualReceivedAmount());
            data.add(row);
        }

        // 表头样式
        WriteCellStyle headStyle = new WriteCellStyle();
        WriteFont headFont = new WriteFont();
        headFont.setFontHeightInPoints((short) 11);
        headFont.setBold(true);
        headStyle.setWriteFont(headFont);

        try (OutputStream os = response.getOutputStream()) {
            EasyExcel.write(os)
                    .head(head)
                    .registerWriteHandler(new com.alibaba.excel.write.style.HorizontalCellStyleStrategy(headStyle, new WriteCellStyle()))
                    .sheet("利润报表")
                    .doWrite(data);
        }
        log.info("[利润] 导出报表完成，共 {} 条", list.size());
    }

    // ==================== 多维度聚合接口（店铺/成本/对账/亏损订单/聚合切换） ====================

    /** 允许的聚合维度白名单（防止 SQL 注入） */
    private static final Set<String> AGG_DIMENSIONS = Set.of("platform", "shop_id", "currency");

    /**
     * 统一将前端传入的 period / startDate+endDate 转换为 profit_report.period 字段值。
     * <p>
     * 月份模式：原样返回 YYYYMM；范围模式：拼接为 "startDate~endDate"。
     * 两者均未传时返回 null。
     */
    private String resolvePeriod(String period, String startDate, String endDate) {
        if (period != null && !period.isBlank()) return period;
        if (startDate != null && !startDate.isBlank() && endDate != null && !endDate.isBlank()) {
            return startDate + "~" + endDate;
        }
        return null;
    }

    /**
     * 按店铺汇总利润报表。
     * <p>
     * 用于店铺维度分析卡片，展示各店铺的订单数、营收、成本、利润、实际到账金额。
     *
     * @param period    核算周期（与 startDate+endDate 二选一）
     * @param startDate 起始日期（yyyy-MM-dd，与 period 二选一）
     * @param endDate   结束日期（yyyy-MM-dd，与 period 二选一）
     */
    @GetMapping("/shop-summary")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','OPERATOR')")
    public Result<List<Map<String, Object>>> shopSummary(@RequestParam(required = false) String period,
                                                          @RequestParam(required = false) String startDate,
                                                          @RequestParam(required = false) String endDate) {
        String p = resolvePeriod(period, startDate, endDate);
        if (p == null) return Result.success(List.of());
        return Result.success(profitReportMapper.selectShopSummary(p));
    }

    /**
     * 成本结构汇总：返回当期所有订单的平台费、公共分摊、直接成本合计。
     * <p>
     * 用于"钱花哪去了"卡片，三段成本占比可视化。
     *
     * @param period    核算周期（与 startDate+endDate 二选一）
     * @param startDate 起始日期（yyyy-MM-dd，与 period 二选一）
     * @param endDate   结束日期（yyyy-MM-dd，与 period 二选一）
     */
    @GetMapping("/cost-structure")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','OPERATOR')")
    public Result<Map<String, Object>> costStructure(@RequestParam(required = false) String period,
                                                      @RequestParam(required = false) String startDate,
                                                      @RequestParam(required = false) String endDate) {
        String p = resolvePeriod(period, startDate, endDate);
        if (p == null) return Result.success(Map.of());
        return Result.success(profitReportMapper.selectCostStructure(p));
    }

    /**
     * 对账汇总：返回当期账面利润 vs 实际到账金额对比数据。
     * <p>
     * 用于"账面利润 vs 实际到账利润"对比卡片：
     * <ul>
     *   <li>matched_count：已对账匹配订单数</li>
     *   <li>matched_amount：已匹配订单的实际到账金额</li>
     *   <li>unreceived_count：未到账订单数</li>
     *   <li>diff_count：差异订单数</li>
     *   <li>total_actual_received：实际到账金额合计</li>
     *   <li>total_book_profit：账面利润合计</li>
     *   <li>total_revenue：账面营收合计</li>
     * </ul>
     *
     * @param period    核算周期（与 startDate+endDate 二选一）
     * @param startDate 起始日期（yyyy-MM-dd，与 period 二选一）
     * @param endDate   结束日期（yyyy-MM-dd，与 period 二选一）
     */
    @GetMapping("/reconcile-summary")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','OPERATOR')")
    public Result<Map<String, Object>> reconcileSummary(@RequestParam(required = false) String period,
                                                         @RequestParam(required = false) String startDate,
                                                         @RequestParam(required = false) String endDate) {
        String p = resolvePeriod(period, startDate, endDate);
        if (p == null) return Result.success(Map.of());
        return Result.success(profitReportMapper.selectReconcileSummary(p));
    }

    /**
     * 亏损订单 Top N 清单。
     * <p>
     * 用于亏损订单清单卡片，按利润升序返回亏损最严重的 N 笔订单，默认 N=10。
     *
     * @param period    核算周期（与 startDate+endDate 二选一）
     * @param startDate 起始日期（yyyy-MM-dd，与 period 二选一）
     * @param endDate   结束日期（yyyy-MM-dd，与 period 二选一）
     * @param limit     返回条数，默认 10，最大 100
     */
    @GetMapping("/loss-orders")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','OPERATOR')")
    public Result<List<Map<String, Object>>> lossOrders(@RequestParam(required = false) String period,
                                                         @RequestParam(required = false) String startDate,
                                                         @RequestParam(required = false) String endDate,
                                                         @RequestParam(defaultValue = "10") int limit) {
        String p = resolvePeriod(period, startDate, endDate);
        if (p == null) return Result.success(List.of());
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return Result.success(profitReportMapper.selectLossOrders(p, safeLimit));
    }

    /**
     * 多维聚合切换：按指定维度分组聚合利润数据。
     * <p>
     * dimension 取值（白名单校验）：
     * <ul>
     *   <li>platform：按平台聚合</li>
     *   <li>shop_id：按店铺聚合</li>
     *   <li>currency：按币种聚合</li>
     * </ul>
     * 返回每组的订单数、营收、成本、利润、实际到账金额，前端可绘制柱状图/表格。
     *
     * @param period    核算周期（与 startDate+endDate 二选一）
     * @param startDate 起始日期（yyyy-MM-dd，与 period 二选一）
     * @param endDate   结束日期（yyyy-MM-dd，与 period 二选一）
     * @param dimension 聚合维度：platform / shop_id / currency
     */
    @GetMapping("/aggregate")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','OPERATOR')")
    public Result<List<Map<String, Object>>> aggregate(@RequestParam(required = false) String period,
                                                        @RequestParam(required = false) String startDate,
                                                        @RequestParam(required = false) String endDate,
                                                        @RequestParam(defaultValue = "platform") String dimension) {
        String p = resolvePeriod(period, startDate, endDate);
        if (p == null) return Result.success(List.of());
        // 白名单校验，防止 SQL 注入（${dim} 拼接）
        if (!AGG_DIMENSIONS.contains(dimension)) {
            return Result.error("不支持的聚合维度：" + dimension + "，仅支持 platform / shop_id / currency");
        }
        return Result.success(profitReportMapper.selectAggregateBy(p, dimension));
    }

    /** 对账状态码 → 文案（用于导出 Excel 可读性） */
    private String reconcileStatusName(Integer status) {
        if (status == null) return "未对账";
        return switch (status) {
            case 1 -> "已完成";
            case 2 -> "对账失败";
            case 3 -> "未到账";
            case 4 -> "不明入账";
            default -> "未对账";
        };
    }

    // ==================== C2: 同比 / 多月趋势 ====================

    /**
     * 利润趋势：返回近 N 个月（默认 12）的月度营收/成本/利润折线，以及同比数据。
     * <p>
     * 仅适用于 MONTH 模式（period 为 YYYYMM），RANGE 模式下返回空（RANGE 无月度概念，环比由 B 维度承担）。
     * <ul>
     *   <li>monthlyTrend：近 N 个月每月一行，无核算数据的月份填 0，保证折线横轴连续</li>
     *   <li>yoy：同比 = 去年同月 vs 当期，返回营收/成本/利润的同比变化百分比</li>
     * </ul>
     *
     * @param period 当期 YYYYMM
     * @param months 趋势月数，默认 12，最大 24
     */
    @GetMapping("/trend")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','OPERATOR')")
    public Result<Map<String, Object>> trend(@RequestParam String period,
                                              @RequestParam(defaultValue = "12") int months) {
        log.info("[利润] 查询趋势 period={}, months={}", period, months);
        // 仅支持 YYYYMM 月份模式（RANGE 无月度趋势概念）
        if (period == null || period.length() != 6) {
            return Result.success(Map.of(
                    "monthlyTrend", List.of(),
                    "yoy", Map.of("available", false, "reason", "RANGE 模式不支持月度趋势，请查看环比维度")
            ));
        }
        int safeMonths = Math.max(3, Math.min(months, 24));

        // 1. 近 N 个月趋势（含当期）
        List<String> recentPeriods = buildRecentMonthlyPeriods(period, safeMonths);
        List<Map<String, Object>> rows = profitReportMapper.selectMonthlyTrend(recentPeriods);
        List<Map<String, Object>> monthlyTrend = fillMissingMonths(recentPeriods, rows);

        // 2. 同比：去年同期 YYYYMM
        Map<String, Object> yoy = computeYoy(period);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("monthlyTrend", monthlyTrend);
        result.put("yoy", yoy);
        return Result.success(result);
    }

    /**
     * 计算同比（去年同期 vs 当期）。
     * <p>
     * 去年同期 YYYYMM = 当期 year-1 + month。若去年同月无核算数据则 available=false。
     *
     * @param currentPeriod 当期 YYYYMM
     * @return 同比对比结果 Map
     */
    private Map<String, Object> computeYoy(String currentPeriod) {
        try {
            int year = Integer.parseInt(currentPeriod.substring(0, 4));
            int month = Integer.parseInt(currentPeriod.substring(4, 6));
            String lastYearPeriod = String.format("%04d%02d", year - 1, month);

            List<Map<String, Object>> currentRows = profitReportMapper.selectMonthlyTrend(List.of(currentPeriod));
            List<Map<String, Object>> lastYearRows = profitReportMapper.selectMonthlyTrend(List.of(lastYearPeriod));

            if (currentRows.isEmpty() || lastYearRows.isEmpty()) {
                return Map.of(
                        "available", false,
                        "reason", lastYearRows.isEmpty()
                                ? "去年同期（" + lastYearPeriod + "）无核算数据，无法计算同比"
                                : "当期无核算数据",
                        "currentPeriod", currentPeriod,
                        "lastYearPeriod", lastYearPeriod
                );
            }

            Map<String, Object> current = currentRows.get(0);
            Map<String, Object> lastYear = lastYearRows.get(0);

            BigDecimal currRevenue = toBigDecimal(current.get("total_revenue"));
            BigDecimal currCost = toBigDecimal(current.get("total_cost"));
            BigDecimal currProfit = toBigDecimal(current.get("total_profit"));
            BigDecimal lastRevenue = toBigDecimal(lastYear.get("total_revenue"));
            BigDecimal lastCost = toBigDecimal(lastYear.get("total_cost"));
            BigDecimal lastProfit = toBigDecimal(lastYear.get("total_profit"));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("available", true);
            result.put("currentPeriod", currentPeriod);
            result.put("lastYearPeriod", lastYearPeriod);
            result.put("current", Map.of(
                    "revenue", currRevenue, "cost", currCost, "profit", currProfit,
                    "profitRate", currRevenue.compareTo(BigDecimal.ZERO) == 0
                            ? BigDecimal.ZERO
                            : currProfit.divide(currRevenue, 4, RoundingMode.HALF_UP)
            ));
            result.put("lastYear", Map.of(
                    "revenue", lastRevenue, "cost", lastCost, "profit", lastProfit,
                    "profitRate", lastRevenue.compareTo(BigDecimal.ZERO) == 0
                            ? BigDecimal.ZERO
                            : lastProfit.divide(lastRevenue, 4, RoundingMode.HALF_UP)
            ));
            result.put("revenueYoy", pctChange(lastRevenue, currRevenue));
            result.put("costYoy", pctChange(lastCost, currCost));
            result.put("profitYoy", pctChange(lastProfit, currProfit));
            return result;
        } catch (Exception e) {
            log.warn("[利润] 同比计算失败 period={}: {}", currentPeriod, e.getMessage());
            return Map.of("available", false, "reason", "同比计算异常：" + e.getMessage());
        }
    }

    /** 安全转换 BigDecimal（兼容 Number/String/null） */
    private BigDecimal toBigDecimal(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(v.toString());
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    /** 百分比变化：(curr - prev) / prev * 100，prev 为 0 返回 null */
    private BigDecimal pctChange(BigDecimal prev, BigDecimal curr) {
        if (prev == null || prev.compareTo(BigDecimal.ZERO) == 0) return null;
        return curr.subtract(prev)
                .divide(prev, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }
}

