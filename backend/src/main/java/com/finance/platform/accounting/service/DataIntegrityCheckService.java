package com.finance.platform.accounting.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.platform.common.constant.BusinessConstants;
import com.finance.platform.common.exception.BusinessException;
import com.finance.platform.data.entity.ExchangeRateSnapshot;
import com.finance.platform.data.entity.ExtraCost;
import com.finance.platform.data.entity.RawOrder;
import com.finance.platform.data.mapper.RawOrderMapper;
import com.finance.platform.data.service.ExchangeRateService;
import com.finance.platform.data.service.ExtraCostService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据完整性检查服务
 * <p>
 * 用于利润核算前的数据完整性校验：按用户选择的日期范围逐天检查
 * 平台账单、银行流水、汇率、额外费用等数据的覆盖情况，
 * 发现缺失时返回结构化的缺失明细，便于前端给出精确到日的提示。
 * <p>
 * 检查维度：
 * <ul>
 *   <li>BILL：平台账单（raw_order.source=PLATFORM，按 order_time 日期匹配）</li>
 *   <li>BANK_FLOW：银行流水（raw_order.source=BANK，按 order_time 日期匹配）</li>
 *   <li>EXCHANGE_RATE：汇率（exchange_rate_snapshot，按 rate_date 匹配；CNY 自身无需汇率）</li>
 *   <li>EXTRA_COST：额外费用（extra_cost，按 cost_date 匹配；非强制，缺失仅提醒）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataIntegrityCheckService {

    private final RawOrderMapper rawOrderMapper;
    private final ExchangeRateService exchangeRateService;
    private final ExtraCostService extraCostService;

    /**
     * 检查指定日期范围内的数据完整性
     *
     * @param startDate 起始日期（含，yyyy-MM-dd）
     * @param endDate   结束日期（含，yyyy-MM-dd）
     * @return 缺失明细结构化结果
     */
    public CheckResult check(String startDate, String endDate) {
        if (StrUtil.isBlank(startDate) || StrUtil.isBlank(endDate)) {
            throw new BusinessException("开始日期和结束日期不能为空");
        }
        LocalDate start = LocalDate.parse(startDate);
        LocalDate end = LocalDate.parse(endDate);
        if (start.isAfter(end)) {
            throw new BusinessException("开始日期不能晚于结束日期");
        }
        long totalDays = ChronoUnit.DAYS.between(start, end) + 1;
        if (totalDays > 366) {
            throw new BusinessException("查询日期范围不能超过 366 天");
        }
        log.info("[完整性检查] 范围 {} ~ {} 共 {} 天", start, end, totalDays);

        // 收集各维度已覆盖的日期集合
        Set<LocalDate> billDays = collectRawOrderDays(BusinessConstants.SOURCE_PLATFORM, start, end);
        Set<LocalDate> bankDays = collectRawOrderDays(BusinessConstants.SOURCE_BANK, start, end);
        Set<LocalDate> rateDays = collectExchangeRateDays(start, end);
        Set<LocalDate> costDays = collectExtraCostDays(start, end);

        // 逐天比对，构建缺失明细（按连续缺失区间合并，便于用户阅读）
        List<MissingRange> billRanges = buildMissingRanges(start, end, billDays, "BILL", "平台账单");
        List<MissingRange> bankRanges = buildMissingRanges(start, end, bankDays, "BANK_FLOW", "银行流水");
        List<MissingRange> rateRanges = buildMissingRanges(start, end, rateDays, "EXCHANGE_RATE", "汇率");
        List<MissingRange> costRanges = buildMissingRanges(start, end, costDays, "EXTRA_COST", "额外费用");

        // 多币种汇率覆盖检查：查询范围内订单涉及的非 CNY 币种，
        // 逐币种逐日检查是否有该币种到 CNY 的汇率，缺失则记为 CurrencyRateMissing
        List<CurrencyRateMissing> currencyRateMissing = checkCurrencyRateCoverage(start, end, billDays);

        List<MissingRange> allMissing = new ArrayList<>();
        allMissing.addAll(billRanges);
        allMissing.addAll(bankRanges);
        allMissing.addAll(rateRanges);
        allMissing.addAll(costRanges);

        // 汇总统计
        Map<String, TypeSummary> summary = new LinkedHashMap<>();
        summary.put("BILL", buildSummary("平台账单", billRanges, totalDays));
        summary.put("BANK_FLOW", buildSummary("银行流水", bankRanges, totalDays));
        summary.put("EXCHANGE_RATE", buildSummary("汇率", rateRanges, totalDays));
        summary.put("EXTRA_COST", buildSummary("额外费用", costRanges, totalDays));

        // 是否阻断核算（账单或汇率缺失会阻断；银行流水缺失仅提醒不阻断）
        // 多币种汇率缺失也属于阻断型（无法折算 CNY）
        boolean blocking = !billRanges.isEmpty() || !rateRanges.isEmpty() || !currencyRateMissing.isEmpty();

        return new CheckResult(
                start.toString(), end.toString(), totalDays,
                allMissing, summary, blocking,
                billDays.size(), bankDays.size(), rateDays.size(), costDays.size(),
                currencyRateMissing
        );
    }

    /**
     * 多币种汇率覆盖检查：查询范围内订单涉及的非 CNY 币种，
     * 逐币种逐日检查是否有该币种到 CNY 的汇率。
     * <p>
     * 场景：订单含 USD/EUR/JPY 三种币种，但只有 USD/CNY 汇率时，
     * EUR 和 JPY 无法折算 CNY，应记为缺失。
     *
     * @param start    起始日期
     * @param end      结束日期
     * @param billDays 已有账单的日期集合（只检查这些日期，避免无账单的日期也报汇率缺失）
     * @return 按币种分组的缺失明细
     */
    private List<CurrencyRateMissing> checkCurrencyRateCoverage(LocalDate start, LocalDate end, Set<LocalDate> billDays) {
        // 1. 查询范围内所有平台账单的非 CNY 币种
        List<RawOrder> bills = rawOrderMapper.selectList(new LambdaQueryWrapper<RawOrder>()
                .eq(RawOrder::getSource, BusinessConstants.SOURCE_PLATFORM)
                .ge(RawOrder::getOrderTime, start.atStartOfDay())
                .le(RawOrder::getOrderTime, end.plusDays(1).atStartOfDay().minusNanos(1))
                .isNotNull(RawOrder::getCurrency)
                .ne(RawOrder::getCurrency, BusinessConstants.CURRENCY_CNY)
                .select(RawOrder::getCurrency, RawOrder::getOrderTime));
        if (bills.isEmpty()) {
            return List.of();
        }
        // 按币种分组涉及的日期集合
        Map<String, Set<LocalDate>> currencyDays = new LinkedHashMap<>();
        for (RawOrder o : bills) {
            if (o.getCurrency() == null || o.getOrderTime() == null) continue;
            currencyDays.computeIfAbsent(o.getCurrency().toUpperCase(), k -> new HashSet<>())
                    .add(o.getOrderTime().toLocalDate());
        }
        if (currencyDays.isEmpty()) {
            return List.of();
        }
        // 2. 查询范围内所有汇率快照，按 (fromCurrency, rateDate) 建立索引
        List<ExchangeRateSnapshot> rates = exchangeRateService.list(
                new LambdaQueryWrapper<ExchangeRateSnapshot>()
                        .ge(ExchangeRateSnapshot::getRateDate, start)
                        .le(ExchangeRateSnapshot::getRateDate, end)
                        .eq(ExchangeRateSnapshot::getToCurrency, BusinessConstants.CURRENCY_CNY));
        Map<String, Set<LocalDate>> rateIndex = new LinkedHashMap<>();
        for (ExchangeRateSnapshot r : rates) {
            if (r.getFromCurrency() == null || r.getRateDate() == null) continue;
            rateIndex.computeIfAbsent(r.getFromCurrency().toUpperCase(), k -> new HashSet<>())
                    .add(r.getRateDate());
        }
        // 3. 逐币种检查缺失日期
        List<CurrencyRateMissing> missing = new ArrayList<>();
        for (Map.Entry<String, Set<LocalDate>> entry : currencyDays.entrySet()) {
            String currency = entry.getKey();
            Set<LocalDate> requiredDays = entry.getValue();
            Set<LocalDate> coveredDays = rateIndex.getOrDefault(currency, Set.of());
            // 只检查有账单的日期（无账单的日期不需要汇率）
            List<LocalDate> missingDays = new ArrayList<>();
            for (LocalDate d : requiredDays) {
                if (!coveredDays.contains(d)) {
                    missingDays.add(d);
                }
            }
            if (!missingDays.isEmpty()) {
                // 合并为连续区间
                List<MissingRange> ranges = buildMissingRangesFromList(missingDays, "EXCHANGE_RATE", "汇率(" + currency + "/CNY)");
                missing.add(new CurrencyRateMissing(currency, BusinessConstants.CURRENCY_CNY,
                        missingDays.size(), requiredDays.size(), ranges));
                log.warn("[完整性检查] 币种 {} 在 {} 个日期缺少到 CNY 的汇率（共涉及 {} 天有订单）",
                        currency, missingDays.size(), requiredDays.size());
            }
        }
        return missing;
    }

    /** 从散列日期列表构建连续缺失区间 */
    private List<MissingRange> buildMissingRangesFromList(List<LocalDate> days, String type, String typeName) {
        if (days.isEmpty()) return List.of();
        List<MissingRange> ranges = new ArrayList<>();
        // 排序
        List<LocalDate> sorted = new ArrayList<>(days);
        java.util.Collections.sort(sorted);
        LocalDate rangeStart = sorted.get(0);
        LocalDate prev = rangeStart;
        for (int i = 1; i < sorted.size(); i++) {
            LocalDate cur = sorted.get(i);
            if (cur.equals(prev.plusDays(1))) {
                prev = cur;
            } else {
                ranges.add(new MissingRange(type, typeName, rangeStart.toString(), prev.toString(),
                        (int) ChronoUnit.DAYS.between(rangeStart, prev) + 1));
                rangeStart = cur;
                prev = cur;
            }
        }
        ranges.add(new MissingRange(type, typeName, rangeStart.toString(), prev.toString(),
                (int) ChronoUnit.DAYS.between(rangeStart, prev) + 1));
        return ranges;
    }

    /** 收集 raw_order 表中指定来源在范围内的日期集合 */
    private Set<LocalDate> collectRawOrderDays(String source, LocalDate start, LocalDate end) {
        List<RawOrder> list = rawOrderMapper.selectList(new LambdaQueryWrapper<RawOrder>()
                .eq(RawOrder::getSource, source)
                .ge(RawOrder::getOrderTime, start.atStartOfDay())
                .le(RawOrder::getOrderTime, end.plusDays(1).atStartOfDay().minusNanos(1)));
        Set<LocalDate> days = new HashSet<>();
        for (RawOrder o : list) {
            if (o.getOrderTime() != null) {
                days.add(o.getOrderTime().toLocalDate());
            }
        }
        return days;
    }

    /** 收集汇率快照表在范围内的日期集合 */
    private Set<LocalDate> collectExchangeRateDays(LocalDate start, LocalDate end) {
        List<ExchangeRateSnapshot> list = exchangeRateService.list(
                new LambdaQueryWrapper<ExchangeRateSnapshot>()
                        .ge(ExchangeRateSnapshot::getRateDate, start)
                        .le(ExchangeRateSnapshot::getRateDate, end));
        Set<LocalDate> days = new HashSet<>();
        for (ExchangeRateSnapshot r : list) {
            if (r.getRateDate() != null) {
                days.add(r.getRateDate());
            }
        }
        return days;
    }

    /** 收集额外费用表在范围内的日期集合 */
    private Set<LocalDate> collectExtraCostDays(LocalDate start, LocalDate end) {
        List<ExtraCost> list = extraCostService.list(new LambdaQueryWrapper<ExtraCost>()
                .ge(ExtraCost::getCostDate, start)
                .le(ExtraCost::getCostDate, end));
        Set<LocalDate> days = new HashSet<>();
        for (ExtraCost c : list) {
            if (c.getCostDate() != null) {
                days.add(c.getCostDate());
            }
        }
        return days;
    }

    /**
     * 构建连续缺失区间列表：把范围内"未覆盖"的日期合并为 [start, end] 区间。
     * 例如：8.15~8.31 都没有账单 → 一个 MissingRange(2026-08-15, 2026-08-31)
     */
    private List<MissingRange> buildMissingRanges(LocalDate start, LocalDate end,
                                                   Set<LocalDate> coveredDays,
                                                   String type, String typeName) {
        List<MissingRange> ranges = new ArrayList<>();
        LocalDate cursor = start;
        while (!cursor.isAfter(end)) {
            if (!coveredDays.contains(cursor)) {
                LocalDate rangeStart = cursor;
                LocalDate rangeEnd = cursor;
                // 向后扩展连续缺失日期
                while (!rangeEnd.isAfter(end) && !coveredDays.contains(rangeEnd)) {
                    rangeEnd = rangeEnd.plusDays(1);
                }
                rangeEnd = rangeEnd.minusDays(1);
                ranges.add(new MissingRange(type, typeName, rangeStart.toString(),
                        rangeEnd.toString(), (int) ChronoUnit.DAYS.between(rangeStart, rangeEnd) + 1));
                cursor = rangeEnd.plusDays(1);
            } else {
                cursor = cursor.plusDays(1);
            }
        }
        return ranges;
    }

    private TypeSummary buildSummary(String typeName, List<MissingRange> ranges, long totalDays) {
        long missingDays = ranges.stream().mapToLong(MissingRange::days).sum();
        String firstMissing = ranges.isEmpty() ? null : ranges.get(0).startDate();
        String lastMissing = ranges.isEmpty() ? null : ranges.get(ranges.size() - 1).endDate();
        return new TypeSummary(typeName, totalDays - missingDays, missingDays, firstMissing, lastMissing);
    }

    // ==================== 返回结构 ====================

    /**
     * 完整性检查结果
     *
     * @param startDate       起始日期
     * @param endDate         结束日期
     * @param totalDays       总天数
     * @param missingRanges   所有缺失区间（按类型分组排序）
     * @param summary         各类型汇总
     * @param blocking        是否阻断核算（账单或汇率缺失时为 true）
     * @param billCoveredDays 账单覆盖天数
     * @param bankCoveredDays 银行流水覆盖天数
     * @param rateCoveredDays 汇率覆盖天数
     * @param costCoveredDays 额外费用覆盖天数
     * @param currencyRateMissing 多币种汇率缺失明细（订单涉及但缺少对应币种到 CNY 的汇率）
     */
    public record CheckResult(
            String startDate,
            String endDate,
            long totalDays,
            List<MissingRange> missingRanges,
            Map<String, TypeSummary> summary,
            boolean blocking,
            long billCoveredDays,
            long bankCoveredDays,
            long rateCoveredDays,
            long costCoveredDays,
            List<CurrencyRateMissing> currencyRateMissing
    ) {}

    /**
     * 连续缺失区间
     *
     * @param type     数据类型编码：BILL / BANK_FLOW / EXCHANGE_RATE / EXTRA_COST
     * @param typeName 数据类型中文名：平台账单 / 银行流水 / 汇率 / 额外费用
     * @param startDate 缺失起始日期（含）
     * @param endDate   缺失结束日期（含）
     * @param days      连续缺失天数
     */
    public record MissingRange(
            String type,
            String typeName,
            String startDate,
            String endDate,
            int days
    ) {}

    /**
     * 各类型汇总
     *
     * @param typeName     类型中文名
     * @param coveredDays  已覆盖天数
     * @param missingDays  缺失天数
     * @param firstMissing 首个缺失日期（无缺失为 null）
     * @param lastMissing  末个缺失日期（无缺失为 null）
     */
    public record TypeSummary(
            String typeName,
            long coveredDays,
            long missingDays,
            String firstMissing,
            String lastMissing
    ) {}

    /**
     * 多币种汇率缺失明细
     *
     * @param currency      订单涉及的币种（如 USD/EUR/JPY）
     * @param targetCurrency 目标币种（固定为 CNY）
     * @param missingDays    缺失汇率的天数
     * @param requiredDays   该币种出现订单的总天数
     * @param ranges         缺失区间列表
     */
    public record CurrencyRateMissing(
            String currency,
            String targetCurrency,
            int missingDays,
            int requiredDays,
            List<MissingRange> ranges
    ) {}
}
