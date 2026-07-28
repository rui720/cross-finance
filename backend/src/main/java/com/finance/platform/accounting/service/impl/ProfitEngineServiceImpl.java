package com.finance.platform.accounting.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.finance.platform.accounting.entity.ProfitReport;
import com.finance.platform.accounting.mapper.ProfitReportMapper;
import com.finance.platform.accounting.service.ProfitEngineService;
import com.finance.platform.accounting.vo.ProfitDetailSummaryVO;
import com.finance.platform.common.constant.BusinessConstants;
import com.finance.platform.common.exception.BusinessException;
import com.finance.platform.common.utils.CurrencyConvertUtils;
import com.finance.platform.data.entity.ExtraCost;
import com.finance.platform.data.entity.RawOrder;
import com.finance.platform.data.mapper.RawOrderMapper;
import com.finance.platform.data.service.ExtraCostService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 利润计算引擎实现
 * <p>
 * 取出周期内 raw_order 数据，将平台费 + 公共额外费用汇总为成本池并按金额占比分摊，
 * 同时把"关联订单号"的额外费用直接计入对应订单成本；
 * 逐单计算：人民币金额 = 原始金额折算 CNY；利润 = 人民币金额 - 总成本；
 * 利润率 = 利润 / 人民币金额，最后批量写入 profit_report。
 * <p>
 * 支持两种核算粒度：
 * <ul>
 *   <li>{@link #calculate(String)} 按 YYYYMM 月份周期：period 存为 "202607"</li>
 *   <li>{@link #calculateByRange(String, String)} 按自定义日期范围：period 存为 "2026-08-15~2026-09-15"</li>
 * </ul>
 * <p>
 * 成本构成（混合模式）：
 * <ul>
 *   <li>平台费 raw_order.fee → 进入公共成本池按金额占比分摊</li>
 *   <li>额外费用 extra_cost，order_no 为空 → 进入公共成本池按金额占比分摊</li>
 *   <li>额外费用 extra_cost，order_no 非空 → 直接计入该订单（叠加到分摊结果上）</li>
 * </ul>
 * <p>
 * 分摊策略：硬编码按金额占比分摊（原 cost_allocation_rule 配置层已移除）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfitEngineServiceImpl extends ServiceImpl<ProfitReportMapper, ProfitReport> implements ProfitEngineService {

    /** 日期范围模式下 period 字段的分隔符，形如 "2026-08-15~2026-09-15" */
    private static final String RANGE_SEPARATOR = "~";

    private final ProfitReportMapper profitReportMapper;
    private final RawOrderMapper rawOrderMapper;
    private final CurrencyConvertUtils currencyConvertUtils;
    private final ExtraCostService extraCostService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void calculate(String period) {
        if (StrUtil.isBlank(period)) {
            throw new BusinessException("核算周期不能为空");
        }
        log.info("[核算] 开始利润核算 period={}", period);
        // 查出周期内平台账单数据（仅 source=PLATFORM，银行流水仅作对账参照，不参与利润核算）
        List<RawOrder> orders = rawOrderMapper.selectList(new LambdaQueryWrapper<RawOrder>()
                .eq(RawOrder::getSource, BusinessConstants.SOURCE_PLATFORM)
                .apply("DATE_FORMAT(order_time, '%Y%m') = {0}", period));
        if (orders.isEmpty()) {
            log.warn("[核算] 周期 {} 无订单数据", period);
            return;
        }
        // 幂等：先清除该周期旧报表，避免重复核算产生脏数据
        profitReportMapper.delete(new LambdaQueryWrapper<ProfitReport>()
                .eq(ProfitReport::getPeriod, period));

        // ============ 1. 公共成本池 = 无订单号的额外费用（平台费直接归属各订单，不再进公共池）============
        // 加载周期内额外费用
        List<ExtraCost> extraCosts = extraCostService.listByPeriod(period);
        // orderNo 非空 → 直接计入；为空 → 进入公共成本池
        Map<String, BigDecimal> directCostByOrderNo = new HashMap<>();
        BigDecimal extraCostPool = BigDecimal.ZERO;
        int skippedZeroCny = 0;
        for (ExtraCost c : extraCosts) {
            BigDecimal cny = extraCostToCny(c);
            if (cny == null) {
                skippedZeroCny++;
                continue;
            }
            if (StrUtil.isNotBlank(c.getOrderNo())) {
                directCostByOrderNo.merge(c.getOrderNo(), cny, BigDecimal::add);
            } else {
                extraCostPool = extraCostPool.add(cny);
            }
        }
        if (skippedZeroCny > 0) {
            log.warn("[核算] 周期 {} 有 {} 笔额外费用因 CNY 折算失败被跳过，请检查汇率是否缺失",
                    period, skippedZeroCny);
        }
        log.info("[核算] 周期 {} 公共额外费用池={}, 关联订单额外费用 {} 笔",
                period, extraCostPool, directCostByOrderNo.size());

        // 按金额占比分摊公共成本池到各订单（配置层已移除，核算引擎硬编码 AMOUNT 策略）
        Map<String, BigDecimal> sharedCostMap = new HashMap<>();
        for (int i = 0; i < orders.size(); i++) {
            BigDecimal share = allocateByAmount(extraCostPool, orders, i);
            sharedCostMap.put(orders.get(i).getOrderNo(), share);
        }

        // ============ 2. 逐单计算利润（成本拆分：平台费+公共分摊+直接成本）============
        List<ProfitReport> reports = new ArrayList<>(orders.size());
        for (RawOrder order : orders) {
            BigDecimal originalAmount = order.getAmount() == null ? BigDecimal.ZERO : order.getAmount();
            BigDecimal cnyAmount = currencyConvertUtils.toCny(originalAmount, order.getCurrency());
            // 平台费直接归属该订单
            BigDecimal feeCost = order.getFee() == null ? BigDecimal.ZERO : order.getFee();
            BigDecimal sharedCost = sharedCostMap.getOrDefault(order.getOrderNo(), BigDecimal.ZERO);
            BigDecimal directCost = directCostByOrderNo.getOrDefault(order.getOrderNo(), BigDecimal.ZERO);
            BigDecimal costAmount = feeCost.add(sharedCost).add(directCost);
            BigDecimal profit = cnyAmount.subtract(costAmount);
            BigDecimal profitRate = cnyAmount.compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO
                    : profit.divide(cnyAmount, 6, RoundingMode.HALF_UP);

            ProfitReport report = buildReport(period, order, originalAmount, cnyAmount,
                    feeCost, sharedCost, directCost, costAmount, profit, profitRate);
            reports.add(report);
        }
        // 批量插入 profit_report
        this.saveBatch(reports);
        log.info("[核算] 周期 {} 利润核算完成，共生成 {} 条报表", period, reports.size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void calculateByRange(String startDate, String endDate) {
        if (StrUtil.isBlank(startDate) || StrUtil.isBlank(endDate)) {
            throw new BusinessException("起始日期和结束日期不能为空");
        }
        LocalDate start = LocalDate.parse(startDate);
        LocalDate end = LocalDate.parse(endDate);
        if (start.isAfter(end)) {
            throw new BusinessException("起始日期不能晚于结束日期");
        }
        // period 字段形如 "2026-08-15~2026-09-15"
        String period = startDate + RANGE_SEPARATOR + endDate;
        log.info("[核算] 开始利润核算 range={}~{}", startDate, endDate);

        // 按 order_time 在 [start, end+1day) 范围过滤（仅 source=PLATFORM，银行流水不参与核算）
        LocalDateTime startDt = start.atStartOfDay();
        LocalDateTime endDt = end.plusDays(1).atStartOfDay().minusNanos(1);
        List<RawOrder> orders = rawOrderMapper.selectList(new LambdaQueryWrapper<RawOrder>()
                .eq(RawOrder::getSource, BusinessConstants.SOURCE_PLATFORM)
                .ge(RawOrder::getOrderTime, startDt)
                .le(RawOrder::getOrderTime, endDt));
        if (orders.isEmpty()) {
            log.warn("[核算] 范围 {}~{} 无订单数据", startDate, endDate);
            return;
        }
        // 幂等：先清除该范围旧报表
        profitReportMapper.delete(new LambdaQueryWrapper<ProfitReport>()
                .eq(ProfitReport::getPeriod, period));

        // ============ 1. 公共成本池 = 无订单号的额外费用（平台费直接归属各订单，不再进公共池）============
        // 加载范围内额外费用（按 cost_date 过滤）
        List<ExtraCost> extraCosts = extraCostService.list(new LambdaQueryWrapper<ExtraCost>()
                .ge(ExtraCost::getCostDate, start)
                .le(ExtraCost::getCostDate, end)
                .eq(ExtraCost::getStatus, 1));
        Map<String, BigDecimal> directCostByOrderNo = new HashMap<>();
        BigDecimal extraCostPool = BigDecimal.ZERO;
        int skippedZeroCny = 0;
        for (ExtraCost c : extraCosts) {
            BigDecimal cny = extraCostToCny(c);
            if (cny == null) {
                skippedZeroCny++;
                continue;
            }
            if (StrUtil.isNotBlank(c.getOrderNo())) {
                directCostByOrderNo.merge(c.getOrderNo(), cny, BigDecimal::add);
            } else {
                extraCostPool = extraCostPool.add(cny);
            }
        }
        if (skippedZeroCny > 0) {
            log.warn("[核算] 范围 {}~{} 有 {} 笔额外费用因 CNY 折算失败被跳过，请检查汇率是否缺失",
                    startDate, endDate, skippedZeroCny);
        }
        log.info("[核算] 范围 {}~{} 公共额外费用池={}, 关联订单额外费用 {} 笔",
                startDate, endDate, extraCostPool, directCostByOrderNo.size());

        // ============ 2. 按金额占比分摊公共池（配置层已移除，硬编码 AMOUNT 策略）============
        Map<String, BigDecimal> sharedCostMap = new HashMap<>();
        for (int i = 0; i < orders.size(); i++) {
            BigDecimal share = allocateByAmount(extraCostPool, orders, i);
            sharedCostMap.put(orders.get(i).getOrderNo(), share);
        }

        // ============ 3. 逐单计算利润（成本拆分：平台费+公共分摊+直接成本）============
        List<ProfitReport> reports = new ArrayList<>(orders.size());
        for (RawOrder order : orders) {
            BigDecimal originalAmount = order.getAmount() == null ? BigDecimal.ZERO : order.getAmount();
            BigDecimal cnyAmount = currencyConvertUtils.toCny(originalAmount, order.getCurrency());
            // 平台费直接归属该订单
            BigDecimal feeCost = order.getFee() == null ? BigDecimal.ZERO : order.getFee();
            BigDecimal sharedCost = sharedCostMap.getOrDefault(order.getOrderNo(), BigDecimal.ZERO);
            BigDecimal directCost = directCostByOrderNo.getOrDefault(order.getOrderNo(), BigDecimal.ZERO);
            BigDecimal costAmount = feeCost.add(sharedCost).add(directCost);
            BigDecimal profit = cnyAmount.subtract(costAmount);
            BigDecimal profitRate = cnyAmount.compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO
                    : profit.divide(cnyAmount, 6, RoundingMode.HALF_UP);

            ProfitReport report = buildReport(period, order, originalAmount, cnyAmount,
                    feeCost, sharedCost, directCost, costAmount, profit, profitRate);
            reports.add(report);
        }
        this.saveBatch(reports);
        log.info("[核算] 范围 {}~{} 利润核算完成，共生成 {} 条报表", startDate, endDate, reports.size());
    }

    @Override
    public Page<ProfitReport> getReport(String period, String startDate, String endDate, int page, int size) {
        Page<ProfitReport> pageObj = new Page<>(page, size);
        LambdaQueryWrapper<ProfitReport> wrapper = buildReportWrapper(period, startDate, endDate);
        return profitReportMapper.selectPage(pageObj, wrapper);
    }

    @Override
    public List<ProfitReport> getReportList(String period, String startDate, String endDate) {
        LambdaQueryWrapper<ProfitReport> wrapper = buildReportWrapper(period, startDate, endDate);
        // 限制最多 10000 条，防止内存溢出
        wrapper.last("LIMIT 10000");
        return profitReportMapper.selectList(wrapper);
    }

    /** 构建利润报表查询条件（period / startDate+endDate / 全部 三种过滤） */
    private LambdaQueryWrapper<ProfitReport> buildReportWrapper(String period, String startDate, String endDate) {
        LambdaQueryWrapper<ProfitReport> wrapper = new LambdaQueryWrapper<ProfitReport>()
                .orderByDesc(ProfitReport::getId);
        if (StrUtil.isNotBlank(period)) {
            // 按月份周期精确匹配
            wrapper.eq(ProfitReport::getPeriod, period);
        } else if (StrUtil.isNotBlank(startDate) && StrUtil.isNotBlank(endDate)) {
            // 按日期范围匹配：period 字段形如 "2026-08-15~2026-09-15"
            String rangePeriod = startDate + RANGE_SEPARATOR + endDate;
            wrapper.eq(ProfitReport::getPeriod, rangePeriod);
        }
        // 否则返回全部
        return wrapper;
    }

    /**
     * 构建带筛选条件的查询 wrapper（在 period/日期范围基础上叠加平台/店铺/币种/对账状态）
     * <p>
     * 用于利润明细页的"先筛选再分页"，保证每页记录数等于分页大小。
     */
    private LambdaQueryWrapper<ProfitReport> buildFilteredWrapper(String period, String startDate, String endDate,
                                                                    String platform, String shopId, String currency,
                                                                    Integer reconcileStatus) {
        LambdaQueryWrapper<ProfitReport> wrapper = buildReportWrapper(period, startDate, endDate);
        wrapper.eq(StrUtil.isNotBlank(platform), ProfitReport::getPlatform, platform)
                .eq(StrUtil.isNotBlank(shopId), ProfitReport::getShopId, shopId)
                .eq(StrUtil.isNotBlank(currency), ProfitReport::getCurrency, currency)
                .eq(reconcileStatus != null, ProfitReport::getReconcileStatus, reconcileStatus);
        return wrapper;
    }

    @Override
    public Page<ProfitReport> getReportFiltered(String period, String startDate, String endDate,
                                                  String platform, String shopId, String currency,
                                                  Integer reconcileStatus, int page, int size) {
        Page<ProfitReport> pageObj = new Page<>(page, size);
        LambdaQueryWrapper<ProfitReport> wrapper = buildFilteredWrapper(
                period, startDate, endDate, platform, shopId, currency, reconcileStatus);
        return profitReportMapper.selectPage(pageObj, wrapper);
    }

    @Override
    public ProfitDetailSummaryVO getDetailSummary(String period, String startDate, String endDate,
                                                    String platform, String shopId, String currency,
                                                    Integer reconcileStatus) {
        LambdaQueryWrapper<ProfitReport> wrapper = buildFilteredWrapper(
                period, startDate, endDate, platform, shopId, currency, reconcileStatus);
        List<ProfitReport> list = profitReportMapper.selectList(wrapper);

        ProfitDetailSummaryVO vo = new ProfitDetailSummaryVO();
        vo.setOrderCount((long) list.size());
        if (list.isEmpty()) {
            vo.setTotalBillAmount(BigDecimal.ZERO);
            vo.setTotalPlatformFee(BigDecimal.ZERO);
            vo.setTotalSharedCost(BigDecimal.ZERO);
            vo.setTotalDirectCost(BigDecimal.ZERO);
            vo.setTotalCostAmount(BigDecimal.ZERO);
            vo.setTotalProfitAmount(BigDecimal.ZERO);
            vo.setProfitRate(BigDecimal.ZERO);
            return vo;
        }
        // 全精度 BigDecimal 求和，不截断小数位
        BigDecimal bill = list.stream().map(ProfitReport::getCnyAmount)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal fee = list.stream().map(ProfitReport::getFeeCost)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal shared = list.stream().map(ProfitReport::getSharedCost)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal direct = list.stream().map(ProfitReport::getDirectCost)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cost = list.stream().map(ProfitReport::getCostAmount)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal profit = list.stream().map(ProfitReport::getProfitAmount)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        // 利润率 = 利润 / 账单金额，全精度计算
        BigDecimal rate = bill.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : profit.divide(bill, 6, RoundingMode.HALF_UP);

        vo.setTotalBillAmount(bill);
        vo.setTotalPlatformFee(fee);
        vo.setTotalSharedCost(shared);
        vo.setTotalDirectCost(direct);
        vo.setTotalCostAmount(cost);
        vo.setTotalProfitAmount(profit);
        vo.setProfitRate(rate);
        return vo;
    }

    @Override
    public Map<String, List<String>> getFilterOptions(String period, String startDate, String endDate) {
        // 用基础 wrapper（仅 period/日期范围过滤，不叠加筛选条件）
        LambdaQueryWrapper<ProfitReport> wrapper = buildReportWrapper(period, startDate, endDate);
        // 只查需要的三个字段，减少内存占用
        wrapper.select(ProfitReport::getPlatform, ProfitReport::getShopId, ProfitReport::getCurrency);
        List<ProfitReport> list = profitReportMapper.selectList(wrapper);

        // 去重并过滤空值，保持稳定顺序
        List<String> platforms = list.stream().map(ProfitReport::getPlatform)
                .filter(StrUtil::isNotBlank).distinct().sorted().collect(java.util.stream.Collectors.toList());
        List<String> shops = list.stream().map(ProfitReport::getShopId)
                .filter(StrUtil::isNotBlank).distinct().sorted().collect(java.util.stream.Collectors.toList());
        List<String> currencies = list.stream().map(ProfitReport::getCurrency)
                .filter(StrUtil::isNotBlank).distinct().sorted().collect(java.util.stream.Collectors.toList());

        Map<String, List<String>> options = new LinkedHashMap<>();
        options.put("platforms", platforms);
        options.put("shops", shops);
        options.put("currencies", currencies);
        return options;
    }

    // ==================== 分摊与成本折算工具方法 ====================

    /**
     * 按订单金额占比分摊总成本到指定下标的订单。
     * <p>
     * 逻辑要点：
     * <ul>
     *   <li>金额越大的订单承担越多成本，分摊基数 = totalCost × orderAmount / sumAmount</li>
     *   <li>最后一笔订单吸收除法尾差，保证各订单分摊之和精确等于 totalCost</li>
     *   <li>订单金额总和为 0 时退化为均分，避免除零</li>
     *   <li>totalCost 为 null 或订单列表为空时返回 0</li>
     * </ul>
     * 该方法替代了原 AmountAllocationStrategy，配置层移除后核算引擎硬编码使用此策略。
     *
     * @param totalCost 待分摊的总成本
     * @param orders    当前周期内的全部订单列表
     * @param index     当前订单在 orders 中的下标
     * @return 当前订单的分摊成本
     */
    private BigDecimal allocateByAmount(BigDecimal totalCost, List<RawOrder> orders, int index) {
        int size = orders.size();
        if (size <= 0 || totalCost == null) {
            return BigDecimal.ZERO;
        }
        // 计算订单金额总和
        BigDecimal sum = BigDecimal.ZERO;
        for (RawOrder o : orders) {
            sum = sum.add(o.getAmount() == null ? BigDecimal.ZERO : o.getAmount());
        }
        // 金额总和为 0 时退化为均分，最后一笔吸收尾差
        if (sum.compareTo(BigDecimal.ZERO) == 0) {
            BigDecimal base = totalCost.divide(BigDecimal.valueOf(size), 2, RoundingMode.HALF_UP);
            if (index < size - 1) {
                return base;
            }
            return totalCost.subtract(base.multiply(BigDecimal.valueOf(size - 1L)));
        }
        // 非最后一笔：按金额占比计算分摊
        if (index < size - 1) {
            BigDecimal orderAmount = orders.get(index).getAmount() == null
                    ? BigDecimal.ZERO : orders.get(index).getAmount();
            return totalCost.multiply(orderAmount).divide(sum, 2, RoundingMode.HALF_UP);
        }
        // 最后一笔吸收尾差：totalCost - 前面所有订单已分摊之和
        BigDecimal allocatedBefore = BigDecimal.ZERO;
        for (int i = 0; i < size - 1; i++) {
            BigDecimal orderAmount = orders.get(i).getAmount() == null
                    ? BigDecimal.ZERO : orders.get(i).getAmount();
            allocatedBefore = allocatedBefore.add(
                    totalCost.multiply(orderAmount).divide(sum, 2, RoundingMode.HALF_UP));
        }
        return totalCost.subtract(allocatedBefore);
    }

    /**
     * 额外费用折算为 CNY。
     * <p>
     * 修复原逻辑缺陷：当 cnyAmount 为空时直接回退用原币 amount 当 CNY 累加，
     * 会把外币金额当人民币计入导致成本失真。
     * <p>
     * 现策略：
     * <ol>
     *   <li>cnyAmount 非空 → 直接使用</li>
     *   <li>cnyAmount 为空且币种为 CNY → 用 amount</li>
     *   <li>cnyAmount 为空且币种非 CNY → 调汇率工具折算；折算失败返回 null（调用方跳过并告警）</li>
     * </ol>
     */
    private BigDecimal extraCostToCny(ExtraCost c) {
        if (c.getCnyAmount() != null && c.getCnyAmount().compareTo(BigDecimal.ZERO) != 0) {
            return c.getCnyAmount();
        }
        BigDecimal amount = c.getAmount() == null ? BigDecimal.ZERO : c.getAmount();
        String currency = c.getCurrency();
        if (amount.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        if ("CNY".equalsIgnoreCase(currency)) {
            return amount;
        }
        // 外币且无预折算 CNY 值：实时折算，失败返回 null
        BigDecimal converted = currencyConvertUtils.toCny(amount, currency);
        if (converted == null) {
            log.warn("[核算] 额外费用 id={} 币种={} 金额={} 折算 CNY 失败（汇率可能缺失），已跳过",
                    c.getId(), currency, amount);
            return null;
        }
        return converted;
    }

    /**
     * 构建 ProfitReport 实体（统一赋值店铺、订单时间、对账数据、成本拆分等新字段）。
     * 供 calculate 与 calculateByRange 复用，保证两套核算路径字段一致。
     */
    private ProfitReport buildReport(String period, RawOrder order, BigDecimal originalAmount,
                                     BigDecimal cnyAmount, BigDecimal feeCost, BigDecimal sharedCost,
                                     BigDecimal directCost, BigDecimal costAmount, BigDecimal profit,
                                     BigDecimal profitRate) {
        ProfitReport report = new ProfitReport();
        report.setPeriod(period);
        report.setOrderNo(order.getOrderNo());
        report.setPlatform(order.getPlatform());
        report.setShopId(order.getShopId());
        report.setCurrency(order.getCurrency());
        report.setOriginalAmount(originalAmount);
        report.setCnyAmount(cnyAmount);
        report.setFeeCost(feeCost);
        report.setSharedCost(sharedCost);
        report.setDirectCost(directCost);
        report.setCostAmount(costAmount);
        report.setProfitAmount(profit);
        report.setProfitRate(profitRate);
        report.setOrderTime(order.getOrderTime());
        report.setReconcileStatus(order.getReconcileStatus() == null ? 0 : order.getReconcileStatus());
        report.setActualReceivedAmount(buildActualReceived(order, cnyAmount));
        return report;
    }

    /**
     * 计算实际到账金额（CNY），按新的 reconcile_status 语义：
     * <ul>
     *   <li>0 未对账 / 3 未到账（原 PLATFORM_ONLY）/ 4 不明入账（原 BANK_ONLY，银行记录不应参与利润核算）→ 0</li>
     *   <li>1 已完成 / 2 对账失败 → 按结算金额 settleAmount 折算 CNY（settleAmount 为空则按账面 cnyAmount）</li>
     * </ul>
     * 未到账订单 actualReceivedAmount=0，可使报表区分"账面利润"与"实际到账利润"。
     */
    private BigDecimal buildActualReceived(RawOrder order, BigDecimal cnyAmount) {
        Integer status = order.getReconcileStatus();
        // 未对账 / 未到账 / 不明入账 → 0
        if (status == null || status == 0
                || status == RawOrder.RECONCILE_UNRECEIVED
                || status == RawOrder.RECONCILE_UNKNOWN) {
            return BigDecimal.ZERO;
        }
        // 已完成(1) / 对账失败(2) → 按结算金额折算
        if (order.getSettleAmount() != null && order.getSettleAmount().compareTo(BigDecimal.ZERO) != 0) {
            BigDecimal received = currencyConvertUtils.toCny(order.getSettleAmount(), order.getCurrency());
            return received == null ? cnyAmount : received;
        }
        // 结算金额为空，按账面 cnyAmount
        return cnyAmount;
    }
}
