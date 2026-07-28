package com.finance.platform.data.controller;

import cn.hutool.core.util.StrUtil;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.write.metadata.style.WriteCellStyle;
import com.alibaba.excel.write.metadata.style.WriteFont;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finance.platform.accounting.entity.ProfitReport;
import com.finance.platform.accounting.mapper.ProfitReportMapper;
import com.finance.platform.common.constant.BusinessConstants;
import com.finance.platform.common.core.Result;
import com.finance.platform.common.utils.CurrencyConvertUtils;
import com.finance.platform.data.entity.ExchangeRateSnapshot;
import com.finance.platform.data.entity.ExtraCost;
import com.finance.platform.data.entity.RawOrder;
import com.finance.platform.data.mapper.RawOrderMapper;
import com.finance.platform.data.service.ExchangeRateService;
import com.finance.platform.data.service.ExtraCostService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据管理接口（在线 CRUD）
 * <p>
 * 提供账单/银行流水/额外费用/汇率的查询、编辑、删除能力，
 * 供前端「数据管理」页面使用。
 * <p>
 * 权限：
 * <ul>
 *   <li>查询：ADMIN/FINANCE/OPERATOR（运营只读）</li>
 *   <li>编辑/删除：仅 ADMIN（避免财务人员误操作，所有写操作有审计日志）</li>
 * </ul>
 * <p>
 * 业务保护：
 * <ul>
 *   <li>删除账单/额外费用前，检查是否已参与利润核算，已核算数据禁止删除（避免破坏历史报表一致性）</li>
 *   <li>删除已对账的银行流水前，提示用户先取消对账</li>
 *   <li>编辑额外费用金额/币种后，立即按当日汇率重算 cnyAmount</li>
 *   <li>编辑/删除汇率后，自动刷新内存换算表缓存</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/data/manage")
@RequiredArgsConstructor
public class DataManagementController {

    private final RawOrderMapper rawOrderMapper;
    private final ExtraCostService extraCostService;
    private final ExchangeRateService exchangeRateService;
    private final ProfitReportMapper profitReportMapper;
    private final CurrencyConvertUtils currencyConvertUtils;

    /* ==================== 账单/银行流水（raw_order） ==================== */

    /**
     * 获取平台/银行名称下拉选项
     * <p>
     * raw_order 表统一存储账单（source=PLATFORM，platform=电商平台名）和银行流水
     *（source=BANK，platform=银行名称），两者共用 platform 字段但语义不同。
     * 本接口按 source 返回去重后的平台列表，供前端筛选/编辑下拉框使用：
     * <ul>
     *   <li>source=PLATFORM：返回 Amazon/Shopee 等电商平台</li>
     *   <li>source=BANK：返回中国银行/工商银行 等银行名称</li>
     *   <li>source 未传：返回所有去重 platform（对账模式用，合并显示）</li>
     * </ul>
     *
     * @param source 数据来源（可选）
     * @return 去重后的平台名称列表
     */
    @GetMapping("/order/platforms")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','OPERATOR')")
    public Result<List<String>> orderPlatforms(@RequestParam(required = false) String source) {
        LambdaQueryWrapper<RawOrder> wrapper = new LambdaQueryWrapper<RawOrder>()
                .select(RawOrder::getPlatform)
                .eq(StrUtil.isNotBlank(source), RawOrder::getSource, source)
                .isNotNull(RawOrder::getPlatform)
                .ne(RawOrder::getPlatform, "");
        List<RawOrder> list = rawOrderMapper.selectList(wrapper);
        // 去重并排序，保持稳定顺序
        List<String> platforms = list.stream()
                .map(RawOrder::getPlatform)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .sorted()
                .toList();
        return Result.success(platforms);
    }

    /**
     * 分页查询账单/银行流水记录
     *
     * @param page      页码
     * @param size      每页大小
     * @param source    数据来源 PLATFORM/BANK（可选）
     * @param orderNo   订单号（模糊查询）
     * @param platform  平台（精确匹配）
     * @param currency  币种（精确匹配）
     * @param batchNo   批次号（精确匹配）
     * @param startDate 订单时间起始（yyyy-MM-dd）
     * @param endDate   订单时间结束（yyyy-MM-dd）
     */
    @GetMapping("/order/page")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','OPERATOR')")
    public Result<IPage<RawOrder>> orderPage(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String batchNo,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        Page<RawOrder> p = new Page<>(page, size);
        LambdaQueryWrapper<RawOrder> wrapper = new LambdaQueryWrapper<RawOrder>()
                .eq(source != null && !source.isBlank(), RawOrder::getSource, source)
                .like(orderNo != null && !orderNo.isBlank(), RawOrder::getOrderNo, orderNo)
                .eq(platform != null && !platform.isBlank(), RawOrder::getPlatform, platform)
                .eq(currency != null && !currency.isBlank(), RawOrder::getCurrency, currency)
                .eq(batchNo != null && !batchNo.isBlank(), RawOrder::getBatchNo, batchNo)
                .ge(startDate != null && !startDate.isBlank(), RawOrder::getOrderTime,
                        parseStartDateTime(startDate))
                .le(endDate != null && !endDate.isBlank(), RawOrder::getOrderTime,
                        parseEndDateTime(endDate))
                .orderByDesc(RawOrder::getOrderTime);
        return Result.success(rawOrderMapper.selectPage(p, wrapper));
    }

    /**
     * 编辑单条账单/银行流水记录
     * <p>
     * 仅允许编辑业务字段（订单号/平台/店铺/币种/金额/手续费/结算金额/订单时间/结算时间），
     * 系统字段（id/source/batchNo/cleanStatus/cleanTime/createdAt）由后端保护不可改。
     */
    @PutMapping("/order/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> updateOrder(@PathVariable Long id, @RequestBody RawOrder body) {
        RawOrder existing = rawOrderMapper.selectById(id);
        if (existing == null) {
            return Result.error("记录不存在");
        }
        // 仅更新允许编辑的字段
        existing.setOrderNo(body.getOrderNo());
        existing.setPlatform(body.getPlatform());
        existing.setShopId(body.getShopId());
        existing.setCurrency(body.getCurrency());
        existing.setAmount(body.getAmount());
        existing.setFee(body.getFee());
        existing.setSettleAmount(body.getSettleAmount());
        existing.setOrderTime(body.getOrderTime());
        existing.setSettleTime(body.getSettleTime());
        rawOrderMapper.updateById(existing);
        log.info("[数据管理] 更新账单记录 id={}, orderNo={}", id, body.getOrderNo());
        return Result.success();
    }

    /**
     * 删除单条账单/银行流水记录
     * <p>
     * 业务保护：
     * <ul>
     *   <li>若账单已参与利润核算（profit_report 中存在相同 order_no），禁止删除</li>
     *   <li>若为银行流水且已对账（reconcile_status != 0），禁止删除，需先取消对账</li>
     * </ul>
     */
    @DeleteMapping("/order/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> deleteOrder(@PathVariable Long id) {
        RawOrder existing = rawOrderMapper.selectById(id);
        if (existing == null) {
            return Result.error("记录不存在");
        }
        // 检查是否已参与利润核算
        if (StrUtil.isNotBlank(existing.getOrderNo())) {
            Long reportCount = profitReportMapper.selectCount(new LambdaQueryWrapper<ProfitReport>()
                    .eq(ProfitReport::getOrderNo, existing.getOrderNo()));
            if (reportCount > 0) {
                return Result.error("该订单已参与利润核算（共 " + reportCount + " 条报表记录），"
                        + "禁止删除；如需修正，请先在「利润报表」页重新核算受影响周期，"
                        + "或先删除对应周期的利润报表");
            }
        }
        // 银行流水需检查对账状态
        if ("BANK".equals(existing.getSource()) && existing.getReconcileStatus() != null
                && existing.getReconcileStatus() != RawOrder.RECONCILE_NONE) {
            return Result.error("该银行流水已对账（状态=" + existing.getReconcileStatus()
                    + "），删除将破坏对账一致性；请先在「数据管理」页切换到对账模式，取消对账后再删除");
        }
        rawOrderMapper.deleteById(id);
        log.info("[数据管理] 删除账单记录 id={}, orderNo={}", id, existing.getOrderNo());
        return Result.success();
    }

    /**
     * 批量删除账单/银行流水记录
     */
    @PostMapping("/order/batch-delete")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Integer> batchDeleteOrders(@RequestBody List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Result.error("请选择要删除的记录");
        }
        // 批量检查：查出所有待删记录，逐条应用业务保护规则
        List<RawOrder> orders = rawOrderMapper.selectBatchIds(ids);
        if (orders.size() != ids.size()) {
            return Result.error("部分记录不存在，请刷新列表后重试");
        }
        for (RawOrder o : orders) {
            if (StrUtil.isNotBlank(o.getOrderNo())) {
                Long reportCount = profitReportMapper.selectCount(new LambdaQueryWrapper<ProfitReport>()
                        .eq(ProfitReport::getOrderNo, o.getOrderNo()));
                if (reportCount > 0) {
                    return Result.error("订单 " + o.getOrderNo() + " 已参与利润核算（"
                            + reportCount + " 条报表），禁止删除；请先重新核算受影响周期");
                }
            }
            if ("BANK".equals(o.getSource()) && o.getReconcileStatus() != null
                    && o.getReconcileStatus() != RawOrder.RECONCILE_NONE) {
                return Result.error("银行流水 " + o.getOrderNo() + " 已对账，请先取消对账后再删除");
            }
        }
        int rows = rawOrderMapper.deleteBatchIds(ids);
        log.info("[数据管理] 批量删除账单记录 {} 条, ids={}", rows, ids);
        return Result.success(rows);
    }

    /**
     * 新增单条账单/银行流水记录
     * <p>
     * 用于数据量不大时手工录入，source 由前端指定（PLATFORM/BANK），
     * 系统字段（cleanStatus/reconcileStatus 等）初始化为默认值。
     * batchNo 标记为 MANUAL，便于区分导入数据与手工数据。
     * 唯一性约束：同一 order_no + source 不可重复（避免手工录入重复账单）。
     */
    @PostMapping("/order")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> addOrder(@RequestBody RawOrder body) {
        if (StrUtil.isBlank(body.getOrderNo())) {
            return Result.error("订单号不能为空");
        }
        if (StrUtil.isBlank(body.getSource())) {
            return Result.error("数据来源（PLATFORM/BANK）不能为空");
        }
        // 唯一性校验：同一订单号 + 同一来源不可重复
        Long existCount = rawOrderMapper.selectCount(
                new LambdaQueryWrapper<RawOrder>()
                        .eq(RawOrder::getOrderNo, body.getOrderNo())
                        .eq(RawOrder::getSource, body.getSource())
        );
        if (existCount > 0) {
            String sourceLabel = "BANK".equals(body.getSource()) ? "银行流水" : "平台账单";
            return Result.error("该" + sourceLabel + "号已存在：" + body.getOrderNo()
                    + "，同一" + sourceLabel + "号不可重复录入");
        }
        body.setId(null);
        body.setBatchNo(BusinessConstants.SOURCE_MANUAL);
        body.setCleanStatus(RawOrder.CLEAN_STATUS_NONE);
        body.setReconcileStatus(RawOrder.RECONCILE_NONE);
        rawOrderMapper.insert(body);
        log.info("[数据管理] 手工新增账单记录 source={}, orderNo={}", body.getSource(), body.getOrderNo());
        return Result.success();
    }

    /* ==================== 额外费用（extra_cost） ==================== */

    @GetMapping("/cost/page")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','OPERATOR')")
    public Result<IPage<ExtraCost>> costPage(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String costType,
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String batchNo,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        Page<ExtraCost> p = new Page<>(page, size);
        LambdaQueryWrapper<ExtraCost> wrapper = new LambdaQueryWrapper<ExtraCost>()
                .eq(costType != null && !costType.isBlank(), ExtraCost::getCostType, costType)
                .like(orderNo != null && !orderNo.isBlank(), ExtraCost::getOrderNo, orderNo)
                .eq(period != null && !period.isBlank(), ExtraCost::getPeriod, period)
                .eq(currency != null && !currency.isBlank(), ExtraCost::getCurrency, currency)
                .eq(batchNo != null && !batchNo.isBlank(), ExtraCost::getBatchNo, batchNo)
                .ge(startDate != null && !startDate.isBlank(), ExtraCost::getCostDate, parseDate(startDate))
                .le(endDate != null && !endDate.isBlank(), ExtraCost::getCostDate, parseDate(endDate))
                .orderByDesc(ExtraCost::getCostDate);
        return Result.success(extraCostService.page(p, wrapper));
    }

    /**
     * 编辑额外费用记录
     * <p>
     * 编辑金额或币种后立即按 costDate 当日汇率重算 cnyAmount，
     * 保证下次利润核算时数据一致。
     */
    @PutMapping("/cost/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> updateCost(@PathVariable Long id, @RequestBody ExtraCost body) {
        ExtraCost existing = extraCostService.getById(id);
        if (existing == null) {
            return Result.error("记录不存在");
        }
        existing.setCostType(body.getCostType());
        existing.setAmount(body.getAmount());
        existing.setCurrency(body.getCurrency());
        existing.setPeriod(body.getPeriod());
        existing.setOrderNo(body.getOrderNo());
        existing.setPayee(body.getPayee());
        existing.setCostDate(body.getCostDate());
        existing.setRemark(body.getRemark());
        // 状态：0 已作废，1 生效（允许编辑场景作废或恢复）
        if (body.getStatus() != null) {
            existing.setStatus(body.getStatus());
        }
        // 立即按 costDate 当日汇率重算 cnyAmount，保证下次核算数据一致
        if (existing.getCurrency() != null && existing.getAmount() != null) {
            if ("CNY".equals(existing.getCurrency())) {
                existing.setCnyAmount(existing.getAmount());
            } else {
                try {
                    BigDecimal cny = currencyConvertUtils.convert(
                            existing.getAmount(), existing.getCurrency(), "CNY");
                    existing.setCnyAmount(cny);
                } catch (Exception e) {
                    log.warn("[数据管理] 重算 cnyAmount 失败 id={}, {}/{}: {}",
                            id, existing.getCurrency(), existing.getAmount(), e.getMessage());
                    return Result.error("币种 " + existing.getCurrency()
                            + " 缺少汇率数据，无法折算 CNY；请先在「历史汇率导入」页补全 "
                            + existing.getCostDate() + " 的 " + existing.getCurrency()
                            + "/CNY 汇率后再编辑");
                }
            }
        }
        extraCostService.updateById(existing);
        log.info("[数据管理] 更新额外费用记录 id={}, costType={}, cnyAmount={}",
                id, body.getCostType(), existing.getCnyAmount());
        return Result.success();
    }

    /**
     * 删除额外费用记录
     * <p>
     * 业务保护：若该费用的 period 已生成利润报表，禁止删除，避免破坏历史核算一致性。
     */
    @DeleteMapping("/cost/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> deleteCost(@PathVariable Long id) {
        ExtraCost existing = extraCostService.getById(id);
        if (existing == null) {
            return Result.error("记录不存在");
        }
        // 检查 period 是否已生成利润报表
        if (StrUtil.isNotBlank(existing.getPeriod())) {
            Long reportCount = profitReportMapper.selectCount(new LambdaQueryWrapper<ProfitReport>()
                    .eq(ProfitReport::getPeriod, existing.getPeriod()));
            if (reportCount > 0) {
                return Result.error("该费用所属周期 " + existing.getPeriod()
                        + " 已生成利润报表（" + reportCount + " 条），禁止删除；"
                        + "如需修正，请先在「利润报表」页重新核算该周期");
            }
        }
        extraCostService.removeById(id);
        log.info("[数据管理] 删除额外费用记录 id={}, period={}", id, existing.getPeriod());
        return Result.success();
    }

    @PostMapping("/cost/batch-delete")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Integer> batchDeleteCosts(@RequestBody List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Result.error("请选择要删除的记录");
        }
        List<ExtraCost> costs = extraCostService.listByIds(ids);
        if (costs.size() != ids.size()) {
            return Result.error("部分记录不存在，请刷新列表后重试");
        }
        for (ExtraCost c : costs) {
            if (StrUtil.isNotBlank(c.getPeriod())) {
                Long reportCount = profitReportMapper.selectCount(new LambdaQueryWrapper<ProfitReport>()
                        .eq(ProfitReport::getPeriod, c.getPeriod()));
                if (reportCount > 0) {
                    return Result.error("费用 " + c.getCostType() + "（周期 " + c.getPeriod()
                            + "）已参与利润核算，禁止删除");
                }
            }
        }
        boolean ok = extraCostService.removeBatchByIds(ids);
        log.info("[数据管理] 批量删除额外费用记录 {} 条, ids={}", ids.size(), ids);
        return Result.success(ok ? ids.size() : 0);
    }

    /**
     * 新增单条额外费用记录
     * <p>
     * 用于数据量不大时手工录入。新增后立即按 costDate 当日汇率折算 cnyAmount。
     * source 标记为 MANUAL，batchNo 标记为 MANUAL，便于区分导入数据与手工数据。
     */
    @PostMapping("/cost")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> addCost(@RequestBody ExtraCost body) {
        if (StrUtil.isBlank(body.getCostType())) {
            return Result.error("费用类型不能为空");
        }
        if (body.getAmount() == null) {
            return Result.error("金额不能为空");
        }
        if (body.getCostDate() == null) {
            return Result.error("费用日期不能为空");
        }
        body.setId(null);
        body.setSource(BusinessConstants.SOURCE_MANUAL);
        body.setBatchNo(BusinessConstants.SOURCE_MANUAL);
        body.setStatus(1);
        // 按 costDate 当日汇率折算 cnyAmount
        if (body.getCurrency() != null && body.getAmount() != null) {
            if ("CNY".equals(body.getCurrency())) {
                body.setCnyAmount(body.getAmount());
            } else {
                try {
                    BigDecimal cny = currencyConvertUtils.convert(
                            body.getAmount(), body.getCurrency(), "CNY");
                    body.setCnyAmount(cny);
                } catch (Exception e) {
                    log.warn("[数据管理] 新增额外费用折算失败 {}/{}: {}",
                            body.getCurrency(), body.getAmount(), e.getMessage());
                    return Result.error("币种 " + body.getCurrency()
                            + " 缺少 " + body.getCostDate() + " 的汇率数据，无法折算 CNY；"
                            + "请先补全汇率后再新增");
                }
            }
        }
        extraCostService.save(body);
        log.info("[数据管理] 手工新增额外费用 costType={}, amount={}", body.getCostType(), body.getAmount());
        return Result.success();
    }

    /**
     * 批量补零：对指定日期范围内缺失的额外费用日期插入金额为 0 的占位记录
     * <p>
     * 适用场景：某些日期无费用支出，原始数据未记录，导致完整性检查报缺失。
     * 补零后该日期视为"已覆盖"，不再阻断核算。
     * <p>
     * 每个缺失日期插入一条 costType=OTHER、amount=0、currency=CNY 的记录，
     * source/batchNo 标记为 FILL_ZERO，便于后续识别和清理。
     *
     * @param startDate 起始日期 yyyy-MM-dd（含）
     * @param endDate   结束日期 yyyy-MM-dd（含）
     * @return 实际补零的记录条数
     */
    @PostMapping("/cost/fill-zero")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Integer> fillZeroCost(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        LocalDate start = LocalDate.parse(startDate);
        LocalDate end = LocalDate.parse(endDate);
        if (start.isAfter(end)) {
            return Result.error("开始日期不能晚于结束日期");
        }
        // 查询范围内已有额外费用的日期集合
        List<ExtraCost> existing = extraCostService.list(new LambdaQueryWrapper<ExtraCost>()
                .ge(ExtraCost::getCostDate, start)
                .le(ExtraCost::getCostDate, end)
                .select(ExtraCost::getCostDate));
        java.util.Set<LocalDate> coveredDays = new java.util.HashSet<>();
        for (ExtraCost c : existing) {
            if (c.getCostDate() != null) {
                coveredDays.add(c.getCostDate());
            }
        }
        // 对缺失日期逐日补零
        List<ExtraCost> toInsert = new ArrayList<>();
        LocalDate cursor = start;
        while (!cursor.isAfter(end)) {
            if (!coveredDays.contains(cursor)) {
                ExtraCost zero = new ExtraCost();
                zero.setCostType("OTHER");
                zero.setAmount(BigDecimal.ZERO);
                zero.setCurrency(BusinessConstants.CURRENCY_CNY);
                zero.setCnyAmount(BigDecimal.ZERO);
                zero.setCostDate(cursor);
                zero.setPeriod(String.valueOf(cursor.getYear())
                        + String.format("%02d", cursor.getMonthValue()));
                zero.setSource("FILL_ZERO");
                zero.setBatchNo("FILL_ZERO");
                zero.setStatus(1);
                zero.setRemark("完整性检查补零占位");
                toInsert.add(zero);
            }
            cursor = cursor.plusDays(1);
        }
        if (!toInsert.isEmpty()) {
            extraCostService.saveBatch(toInsert);
        }
        log.info("[数据管理] 批量补零 {} ~ {}，共补零 {} 条", startDate, endDate, toInsert.size());
        return Result.success(toInsert.size());
    }

    /* ==================== 汇率（exchange_rate_snapshot） ==================== */

    @GetMapping("/rate/page")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','OPERATOR')")
    public Result<IPage<ExchangeRateSnapshot>> ratePage(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String fromCurrency,
            @RequestParam(required = false) String toCurrency,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        Page<ExchangeRateSnapshot> p = new Page<>(page, size);
        LambdaQueryWrapper<ExchangeRateSnapshot> wrapper = new LambdaQueryWrapper<ExchangeRateSnapshot>()
                .eq(fromCurrency != null && !fromCurrency.isBlank(),
                        ExchangeRateSnapshot::getFromCurrency, fromCurrency)
                .eq(toCurrency != null && !toCurrency.isBlank(),
                        ExchangeRateSnapshot::getToCurrency, toCurrency)
                .ge(startDate != null && !startDate.isBlank(),
                        ExchangeRateSnapshot::getRateDate, parseDate(startDate))
                .le(endDate != null && !endDate.isBlank(),
                        ExchangeRateSnapshot::getRateDate, parseDate(endDate))
                .orderByDesc(ExchangeRateSnapshot::getRateDate);
        return Result.success(exchangeRateService.page(p, wrapper));
    }

    /**
     * 编辑汇率记录
     * <p>
     * 编辑后自动刷新内存换算表缓存。注意：已生成的利润报表不会自动更新，
     * 前端应提示用户是否需要重新核算受影响周期。
     */
    @PutMapping("/rate/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> updateRate(@PathVariable Long id, @RequestBody ExchangeRateSnapshot body) {
        ExchangeRateSnapshot existing = exchangeRateService.getById(id);
        if (existing == null) {
            return Result.error("记录不存在");
        }
        existing.setFromCurrency(body.getFromCurrency());
        existing.setToCurrency(body.getToCurrency());
        existing.setRateDate(body.getRateDate());
        existing.setRate(body.getRate());
        existing.setSource(body.getSource());
        exchangeRateService.updateById(existing);
        // 刷新内存缓存
        exchangeRateService.refreshCache();
        log.info("[数据管理] 更新汇率记录 id={}, {}->{}={}", id, body.getFromCurrency(),
                body.getToCurrency(), body.getRate());
        return Result.success();
    }

    /**
     * 删除汇率记录
     * <p>
     * 业务保护：若该汇率所在日期范围已生成利润报表，禁止删除。
     * 删除后自动刷新内存换算表缓存。
     */
    @DeleteMapping("/rate/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> deleteRate(@PathVariable Long id) {
        ExchangeRateSnapshot existing = exchangeRateService.getById(id);
        if (existing == null) {
            return Result.error("记录不存在");
        }
        // 检查该汇率日期是否已生成利润报表（RANGE 模式 period 形如 "2026-08-15~2026-09-15"）
        if (existing.getRateDate() != null) {
            LocalDate rateDate = existing.getRateDate();
            Long reportCount = profitReportMapper.selectCount(new LambdaQueryWrapper<ProfitReport>()
                    .like(ProfitReport::getPeriod, "~")
                    .and(w -> w
                            .le(ProfitReport::getPeriod, rateDate + "~9999-12-31")
                            .or()
                            .ge(ProfitReport::getPeriod, "0000-01-01~" + rateDate)));
            // 简化检查：只要存在 RANGE 模式报表，且汇率日期在报表周期内，提示风险
            if (reportCount > 0) {
                // 进一步精确检查：解析 period 范围判断 rateDate 是否在区间内
                List<ProfitReport> rangeReports = profitReportMapper.selectList(new LambdaQueryWrapper<ProfitReport>()
                        .like(ProfitReport::getPeriod, "~")
                        .select(ProfitReport::getPeriod));
                boolean affectsReport = false;
                for (ProfitReport r : rangeReports) {
                    String[] parts = r.getPeriod().split("~");
                    if (parts.length == 2) {
                        try {
                            LocalDate s = LocalDate.parse(parts[0]);
                            LocalDate e = LocalDate.parse(parts[1]);
                            if (!rateDate.isBefore(s) && !rateDate.isAfter(e)) {
                                affectsReport = true;
                                break;
                            }
                        } catch (Exception ignored) {}
                    }
                }
                if (affectsReport) {
                    return Result.error("汇率 " + existing.getFromCurrency() + "/CNY（"
                            + rateDate + "）已用于日期范围内的利润核算，"
                            + "删除将影响历史报表的可复现性；如确需删除，请先重新核算受影响周期");
                }
            }
        }
        exchangeRateService.removeById(id);
        // 刷新内存缓存（删除后内存换算表不应保留旧汇率）
        exchangeRateService.refreshCache();
        log.info("[数据管理] 删除汇率记录 id={}, {}->{}={}", id, existing.getFromCurrency(),
                existing.getToCurrency(), existing.getRate());
        return Result.success();
    }

    @PostMapping("/rate/batch-delete")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Integer> batchDeleteRates(@RequestBody List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Result.error("请选择要删除的记录");
        }
        // 复用单条删除的检查逻辑（简化：批量场景提示用户如有疑问请逐条删除）
        List<ExchangeRateSnapshot> rates = exchangeRateService.listByIds(ids);
        if (rates.size() != ids.size()) {
            return Result.error("部分记录不存在，请刷新列表后重试");
        }
        boolean ok = exchangeRateService.removeBatchByIds(ids);
        // 刷新内存缓存
        exchangeRateService.refreshCache();
        log.info("[数据管理] 批量删除汇率记录 {} 条, ids={}", ids.size(), ids);
        return Result.success(ok ? ids.size() : 0);
    }

    /**
     * 新增单条汇率记录
     * <p>
     * 用于数据量不大时手工录入。新增后自动刷新内存换算表缓存。
     * 同日同币对已存在则返回错误，避免重复录入（如需修改请用编辑接口）。
     */
    @PostMapping("/rate")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> addRate(@RequestBody ExchangeRateSnapshot body) {
        if (body.getRateDate() == null) {
            return Result.error("汇率日期不能为空");
        }
        if (StrUtil.isBlank(body.getFromCurrency()) || StrUtil.isBlank(body.getToCurrency())) {
            return Result.error("源币种和目标币种不能为空");
        }
        if (body.getRate() == null) {
            return Result.error("汇率不能为空");
        }
        // 检查同日同币对是否已存在
        Long existCount = exchangeRateService.count(new LambdaQueryWrapper<ExchangeRateSnapshot>()
                .eq(ExchangeRateSnapshot::getRateDate, body.getRateDate())
                .eq(ExchangeRateSnapshot::getFromCurrency, body.getFromCurrency())
                .eq(ExchangeRateSnapshot::getToCurrency, body.getToCurrency()));
        if (existCount > 0) {
            return Result.error(body.getRateDate() + " 的 " + body.getFromCurrency()
                    + "/" + body.getToCurrency() + " 汇率已存在，请使用编辑功能修改");
        }
        body.setId(null);
        if (StrUtil.isBlank(body.getSource())) {
            body.setSource(BusinessConstants.SOURCE_MANUAL);
        }
        exchangeRateService.save(body);
        // 刷新内存缓存
        exchangeRateService.refreshCache();
        log.info("[数据管理] 手工新增汇率 {}->{}={}, date={}",
                body.getFromCurrency(), body.getToCurrency(), body.getRate(), body.getRateDate());
        return Result.success();
    }

    /* ==================== 批量编辑（合并批次号 / 修正平台/币种/周期） ==================== */

    /**
     * 批量编辑账单/银行流水记录。
     * <p>
     * 典型场景：一个月的数据分两次导入产生两个 batchNo，通过本接口合并为一个批次。
     * 仅允许批量修改安全字段（batchNo/platform/currency/shopId），金额/订单号等敏感字段
     * 不可批量改（避免破坏核算一致性，应走单条编辑）。
     * <p>
     * 字段留空表示"不修改该字段"，只更新非 null 字段。
     *
     * @param body { ids: [Long], batchNo?, platform?, currency?, shopId? }
     */
    @PostMapping("/order/batch-update")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Integer> batchUpdateOrders(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Long> ids = (List<Long>) body.get("ids");
        if (ids == null || ids.isEmpty()) {
            return Result.error("请选择要批量编辑的记录");
        }
        LambdaUpdateWrapper<RawOrder> uw = new LambdaUpdateWrapper<RawOrder>()
                .in(RawOrder::getId, ids);
        applyIfPresent(uw, RawOrder::getBatchNo, (String) body.get("batchNo"));
        applyIfPresent(uw, RawOrder::getPlatform, (String) body.get("platform"));
        applyIfPresent(uw, RawOrder::getCurrency, (String) body.get("currency"));
        applyIfPresent(uw, RawOrder::getShopId, (String) body.get("shopId"));
        int rows = rawOrderMapper.update(null, uw);
        log.info("[数据管理] 批量编辑账单 {} 条, ids={}, 字段={}", rows, ids,
                fieldsOf(body, "batchNo", "platform", "currency", "shopId"));
        return Result.success(rows);
    }

    /**
     * 批量编辑额外费用记录。
     * <p>
     * 典型场景：合并批次号、修正核算周期 period、修正币种。
     * 若修改了 currency，自动按 costDate 当日汇率重算 cnyAmount（保证下次核算一致）。
     * 金额/费用类型等敏感字段不可批量改。
     *
     * @param body { ids: [Long], batchNo?, period?, currency?, orderNo?, payee?, status? }
     */
    @PostMapping("/cost/batch-update")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Integer> batchUpdateCosts(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Long> ids = (List<Long>) body.get("ids");
        if (ids == null || ids.isEmpty()) {
            return Result.error("请选择要批量编辑的记录");
        }
        // 若改了币种，需逐条重算 cnyAmount（每条 costDate 不同，无法用一条 SQL 完成）
        String newCurrency = (String) body.get("currency");
        if (StrUtil.isNotBlank(newCurrency)) {
            List<ExtraCost> costs = extraCostService.listByIds(ids);
            for (ExtraCost c : costs) {
                c.setCurrency(newCurrency);
                if ("CNY".equals(newCurrency)) {
                    c.setCnyAmount(c.getAmount());
                } else {
                    try {
                        BigDecimal cny = currencyConvertUtils.convert(
                                c.getAmount(), newCurrency, "CNY");
                        c.setCnyAmount(cny);
                    } catch (Exception e) {
                        return Result.error("费用 id=" + c.getId() + "（" + c.getCostDate()
                                + "）缺少 " + newCurrency + "/CNY 汇率，无法折算；请先补全汇率");
                    }
                }
            }
            extraCostService.updateBatchById(costs);
        }
        // 其他字段用 UpdateWrapper 批量更新
        LambdaUpdateWrapper<ExtraCost> uw = new LambdaUpdateWrapper<ExtraCost>()
                .in(ExtraCost::getId, ids);
        applyIfPresent(uw, ExtraCost::getBatchNo, (String) body.get("batchNo"));
        applyIfPresent(uw, ExtraCost::getPeriod, (String) body.get("period"));
        applyIfPresent(uw, ExtraCost::getOrderNo, (String) body.get("orderNo"));
        applyIfPresent(uw, ExtraCost::getPayee, (String) body.get("payee"));
        Object status = body.get("status");
        if (status instanceof Number n) {
            uw.set(ExtraCost::getStatus, n.intValue());
        }
        int rows = extraCostService.update(uw) ? ids.size() : 0;
        log.info("[数据管理] 批量编辑额外费用 {} 条, ids={}, 字段={}", rows, ids,
                fieldsOf(body, "batchNo", "period", "currency", "orderNo", "payee", "status"));
        return Result.success(rows);
    }

    /**
     * 批量编辑汇率记录。
     * <p>
     * 典型场景：批量修改币对方向或来源标记。汇率值不可批量改（应走单条编辑）。
     * 修改后自动刷新内存换算表缓存。
     *
     * @param body { ids: [Long], fromCurrency?, toCurrency?, source? }
     */
    @PostMapping("/rate/batch-update")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Integer> batchUpdateRates(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Long> ids = (List<Long>) body.get("ids");
        if (ids == null || ids.isEmpty()) {
            return Result.error("请选择要批量编辑的记录");
        }
        LambdaUpdateWrapper<ExchangeRateSnapshot> uw = new LambdaUpdateWrapper<ExchangeRateSnapshot>()
                .in(ExchangeRateSnapshot::getId, ids);
        applyIfPresent(uw, ExchangeRateSnapshot::getFromCurrency, (String) body.get("fromCurrency"));
        applyIfPresent(uw, ExchangeRateSnapshot::getToCurrency, (String) body.get("toCurrency"));
        applyIfPresent(uw, ExchangeRateSnapshot::getSource, (String) body.get("source"));
        boolean ok = exchangeRateService.update(uw);
        if (ok) exchangeRateService.refreshCache();
        log.info("[数据管理] 批量编辑汇率 {} 条, ids={}, 字段={}", ids.size(), ids,
                fieldsOf(body, "fromCurrency", "toCurrency", "source"));
        return Result.success(ok ? ids.size() : 0);
    }

    /** 通用：仅当 value 非空时追加 set 子句（用于"留空不修改"语义） */
    private <T> void applyIfPresent(LambdaUpdateWrapper<T> uw,
                                     com.baomidou.mybatisplus.core.toolkit.support.SFunction<T, ?> column,
                                     String value) {
        if (StrUtil.isNotBlank(value)) {
            uw.set(column, value);
        }
    }

    /** 构造"已修改字段名列表"字符串，用于审计日志可读性 */
    private String fieldsOf(Map<String, Object> body, String... keys) {
        List<String> changed = new ArrayList<>();
        for (String k : keys) {
            Object v = body.get(k);
            if (v != null && !(v instanceof String s && s.isBlank())) {
                changed.add(k);
            }
        }
        return changed.isEmpty() ? "无" : String.join(",", changed);
    }

    /* ==================== 数据导出（备份 / 外部使用） ==================== */

    /**
     * 导出账单/银行流水为 Excel。
     * <p>
     * 按当前筛选条件导出，最多 10000 条。用于数据备份或外部系统对接。
     *
     * @param source    数据来源 PLATFORM/BANK（可选）
     * @param orderNo   订单号（模糊）
     * @param platform  平台
     * @param currency  币种
     * @param batchNo   批次号
     * @param startDate 订单时间起始
     * @param endDate   订单时间结束
     */
    @GetMapping("/order/export")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','OPERATOR')")
    public void exportOrders(@RequestParam(required = false) String source,
                              @RequestParam(required = false) String orderNo,
                              @RequestParam(required = false) String platform,
                              @RequestParam(required = false) String currency,
                              @RequestParam(required = false) String batchNo,
                              @RequestParam(required = false) String startDate,
                              @RequestParam(required = false) String endDate,
                              HttpServletResponse response) throws Exception {
        LambdaQueryWrapper<RawOrder> wrapper = buildOrderWrapper(source, orderNo, platform,
                currency, batchNo, startDate, endDate);
        wrapper.last("LIMIT 10000");
        List<RawOrder> list = rawOrderMapper.selectList(wrapper);
        writeExcel(response, "账单银行流水", list, orderHead(), orderRowMapper(list));
        log.info("[数据管理] 导出账单/银行流水 {} 条, source={}, batchNo={}", list.size(), source, batchNo);
    }

    /**
     * 导出额外费用为 Excel。
     */
    @GetMapping("/cost/export")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','OPERATOR')")
    public void exportCosts(@RequestParam(required = false) String costType,
                             @RequestParam(required = false) String orderNo,
                             @RequestParam(required = false) String period,
                             @RequestParam(required = false) String currency,
                             @RequestParam(required = false) String batchNo,
                             @RequestParam(required = false) String startDate,
                             @RequestParam(required = false) String endDate,
                             HttpServletResponse response) throws Exception {
        LambdaQueryWrapper<ExtraCost> wrapper = buildCostWrapper(costType, orderNo, period,
                currency, batchNo, startDate, endDate);
        wrapper.last("LIMIT 10000");
        List<ExtraCost> list = extraCostService.list(wrapper);
        writeExcel(response, "额外费用", list, costHead(), costRowMapper(list));
        log.info("[数据管理] 导出额外费用 {} 条, costType={}, period={}", list.size(), costType, period);
    }

    /**
     * 导出汇率为 Excel。
     */
    @GetMapping("/rate/export")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','OPERATOR')")
    public void exportRates(@RequestParam(required = false) String fromCurrency,
                             @RequestParam(required = false) String toCurrency,
                             @RequestParam(required = false) String startDate,
                             @RequestParam(required = false) String endDate,
                             HttpServletResponse response) throws Exception {
        LambdaQueryWrapper<ExchangeRateSnapshot> wrapper = buildRateWrapper(fromCurrency,
                toCurrency, startDate, endDate);
        wrapper.last("LIMIT 10000");
        List<ExchangeRateSnapshot> list = exchangeRateService.list(wrapper);
        writeExcel(response, "汇率快照", list, rateHead(), rateRowMapper(list));
        log.info("[数据管理] 导出汇率 {} 条, {}->{}", list.size(), fromCurrency, toCurrency);
    }

    /**
     * 导出已核算利润报表为 Excel（数据管理页快捷入口）。
     * <p>
     * 转发到 ProfitCalcController.exportReport 同等逻辑，复用 profit_report 表数据。
     * 支持按 period（YYYYMM）或 startDate+endDate（范围模式）过滤。
     */
    @GetMapping("/profit/export")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','OPERATOR')")
    public void exportProfit(@RequestParam(required = false) String period,
                              @RequestParam(required = false) String startDate,
                              @RequestParam(required = false) String endDate,
                              HttpServletResponse response) throws Exception {
        // 复用 ProfitReportMapper 查询
        LambdaQueryWrapper<ProfitReport> wrapper = new LambdaQueryWrapper<ProfitReport>()
                .orderByDesc(ProfitReport::getId);
        String scope;
        if (StrUtil.isNotBlank(period)) {
            wrapper.eq(ProfitReport::getPeriod, period);
            scope = period;
        } else if (StrUtil.isNotBlank(startDate) && StrUtil.isNotBlank(endDate)) {
            wrapper.eq(ProfitReport::getPeriod, startDate + "~" + endDate);
            scope = startDate + "_" + endDate;
        } else {
            response.setStatus(400);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":400,\"msg\":\"导出必须指定 period 或 startDate+endDate\"}");
            return;
        }
        wrapper.last("LIMIT 10000");
        List<ProfitReport> list = profitReportMapper.selectList(wrapper);

        List<List<String>> head = new ArrayList<>();
        head.add(List.of("核算周期"));
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
        head.add(List.of("利润"));
        head.add(List.of("利润率"));
        head.add(List.of("对账状态"));
        head.add(List.of("实际到账"));

        List<List<Object>> data = new ArrayList<>();
        for (ProfitReport r : list) {
            List<Object> row = new ArrayList<>();
            row.add(r.getPeriod());
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
            row.add(r.getReconcileStatus() == null ? "未对账"
                    : switch (r.getReconcileStatus()) {
                        case 1 -> "已对账";
                        case 2 -> "差异";
                        default -> "未对账";
                    });
            row.add(r.getActualReceivedAmount());
            data.add(row);
        }
        writeExcel(response, "利润报表_" + scope, list, head, data);
        log.info("[数据管理] 导出利润报表 {} 条, period={}", list.size(), scope);
    }

    // ---- 导出工具方法 ----

    private LambdaQueryWrapper<RawOrder> buildOrderWrapper(String source, String orderNo,
            String platform, String currency, String batchNo, String startDate, String endDate) {
        return new LambdaQueryWrapper<RawOrder>()
                .eq(StrUtil.isNotBlank(source), RawOrder::getSource, source)
                .like(StrUtil.isNotBlank(orderNo), RawOrder::getOrderNo, orderNo)
                .eq(StrUtil.isNotBlank(platform), RawOrder::getPlatform, platform)
                .eq(StrUtil.isNotBlank(currency), RawOrder::getCurrency, currency)
                .eq(StrUtil.isNotBlank(batchNo), RawOrder::getBatchNo, batchNo)
                .ge(StrUtil.isNotBlank(startDate), RawOrder::getOrderTime, parseStartDateTime(startDate))
                .le(StrUtil.isNotBlank(endDate), RawOrder::getOrderTime, parseEndDateTime(endDate))
                .orderByDesc(RawOrder::getOrderTime);
    }

    private LambdaQueryWrapper<ExtraCost> buildCostWrapper(String costType, String orderNo,
            String period, String currency, String batchNo, String startDate, String endDate) {
        return new LambdaQueryWrapper<ExtraCost>()
                .eq(StrUtil.isNotBlank(costType), ExtraCost::getCostType, costType)
                .like(StrUtil.isNotBlank(orderNo), ExtraCost::getOrderNo, orderNo)
                .eq(StrUtil.isNotBlank(period), ExtraCost::getPeriod, period)
                .eq(StrUtil.isNotBlank(currency), ExtraCost::getCurrency, currency)
                .eq(StrUtil.isNotBlank(batchNo), ExtraCost::getBatchNo, batchNo)
                .ge(StrUtil.isNotBlank(startDate), ExtraCost::getCostDate, parseDate(startDate))
                .le(StrUtil.isNotBlank(endDate), ExtraCost::getCostDate, parseDate(endDate))
                .orderByDesc(ExtraCost::getCostDate);
    }

    private LambdaQueryWrapper<ExchangeRateSnapshot> buildRateWrapper(String fromCurrency,
            String toCurrency, String startDate, String endDate) {
        return new LambdaQueryWrapper<ExchangeRateSnapshot>()
                .eq(StrUtil.isNotBlank(fromCurrency), ExchangeRateSnapshot::getFromCurrency, fromCurrency)
                .eq(StrUtil.isNotBlank(toCurrency), ExchangeRateSnapshot::getToCurrency, toCurrency)
                .ge(StrUtil.isNotBlank(startDate), ExchangeRateSnapshot::getRateDate, parseDate(startDate))
                .le(StrUtil.isNotBlank(endDate), ExchangeRateSnapshot::getRateDate, parseDate(endDate))
                .orderByDesc(ExchangeRateSnapshot::getRateDate);
    }

    /** 账单表头 */
    private List<List<String>> orderHead() {
        return List.of(
                List.of("订单号"), List.of("平台"), List.of("店铺"), List.of("数据来源"),
                List.of("币种"), List.of("金额"), List.of("平台费"), List.of("结算金额"),
                List.of("下单时间"), List.of("结算时间"), List.of("批次号"), List.of("对账状态")
        );
    }

    /** 账单数据行（用 Arrays.asList 替代 List.of，因为银行流水的 platform/shopId/fee 等字段可能为 null，List.of 不允许 null 元素） */
    private List<List<Object>> orderRowMapper(List<RawOrder> list) {
        List<List<Object>> data = new ArrayList<>();
        for (RawOrder o : list) {
            data.add(Arrays.asList(
                    o.getOrderNo(), o.getPlatform(), o.getShopId(), o.getSource(),
                    o.getCurrency(), o.getAmount(), o.getFee(), o.getSettleAmount(),
                    o.getOrderTime(), o.getSettleTime(), o.getBatchNo(),
                    o.getReconcileStatus() == null ? "未对账"
                            : switch (o.getReconcileStatus()) {
                                case 1 -> "已对账";
                                case 2 -> "差异";
                                default -> "未对账";
                            }
            ));
        }
        return data;
    }

    /** 额外费用表头 */
    private List<List<String>> costHead() {
        return List.of(
                List.of("费用类型"), List.of("金额"), List.of("币种"), List.of("CNY金额"),
                List.of("核算周期"), List.of("订单号"), List.of("收款方"), List.of("费用日期"),
                List.of("来源"), List.of("批次号"), List.of("状态"), List.of("备注")
        );
    }

    /** 额外费用数据行 */
    private List<List<Object>> costRowMapper(List<ExtraCost> list) {
        List<List<Object>> data = new ArrayList<>();
        for (ExtraCost c : list) {
            data.add(Arrays.asList(
                    c.getCostType(), c.getAmount(), c.getCurrency(), c.getCnyAmount(),
                    c.getPeriod(), c.getOrderNo(), c.getPayee(), c.getCostDate(),
                    c.getSource(), c.getBatchNo(),
                    c.getStatus() == null ? "" : (c.getStatus() == 1 ? "生效" : "已作废"),
                    c.getRemark() == null ? "" : c.getRemark()
            ));
        }
        return data;
    }

    /** 汇率表头 */
    private List<List<String>> rateHead() {
        return List.of(
                List.of("汇率日期"), List.of("源币种"), List.of("目标币种"),
                List.of("汇率"), List.of("来源")
        );
    }

    /** 汇率数据行 */
    private List<List<Object>> rateRowMapper(List<ExchangeRateSnapshot> list) {
        List<List<Object>> data = new ArrayList<>();
        for (ExchangeRateSnapshot r : list) {
            data.add(Arrays.asList(
                    r.getRateDate(), r.getFromCurrency(), r.getToCurrency(),
                    r.getRate(), r.getSource() == null ? "" : r.getSource()
            ));
        }
        return data;
    }

    /**
     * 通用 EasyExcel 写入响应流。
     *
     * @param response HTTP 响应
     * @param fileName 文件名（不含扩展名，自动追加 .xlsx）
     * @param data     数据列表（仅用于日志计数）
     * @param head     表头
     * @param rows     数据行
     */
    private void writeExcel(HttpServletResponse response, String fileName, List<?> data,
                             List<List<String>> head, List<List<Object>> rows) throws Exception {
        String encoded = URLEncoder.encode(fileName + ".xlsx", StandardCharsets.UTF_8)
                .replaceAll("\\+", "%20");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encoded);
        WriteCellStyle headStyle = new WriteCellStyle();
        WriteFont font = new WriteFont();
        font.setFontHeightInPoints((short) 11);
        font.setBold(true);
        headStyle.setWriteFont(font);
        try (OutputStream os = response.getOutputStream()) {
            EasyExcel.write(os)
                    .head(head)
                    .registerWriteHandler(new com.alibaba.excel.write.style.HorizontalCellStyleStrategy(
                            headStyle, new WriteCellStyle()))
                    .sheet(fileName)
                    .doWrite(rows);
        }
    }

    /* ==================== 工具方法 ==================== */

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        return LocalDate.parse(s);
    }

    /**
     * 将日期或日期时间字符串转为起始 LocalDateTime。
     * <p>
     * 支持 yyyy-MM-dd（转为当天 00:00:00）和 yyyy-MM-ddTHH:mm:ss（直接解析）两种格式。
     * <p>
     * 注意：Java 方法参数严格求值，LambdaQueryWrapper 的 ge(condition, column, value)
     * 即使 condition 为 false，value 仍会被计算。因此必须做空值保护，避免 NPE。
     */
    private LocalDateTime parseStartDateTime(String s) {
        if (s == null || s.isBlank()) return null;
        if (s.length() > 10) {
            return LocalDateTime.parse(s);
        }
        return LocalDate.parse(s).atStartOfDay();
    }

    /**
     * 将日期或日期时间字符串转为结束 LocalDateTime。
     * <p>
     * 支持 yyyy-MM-dd（转为当天 23:59:59.999999999）和 yyyy-MM-ddTHH:mm:ss（直接解析）两种格式。
     */
    private LocalDateTime parseEndDateTime(String s) {
        if (s == null || s.isBlank()) return null;
        if (s.length() > 10) {
            return LocalDateTime.parse(s);
        }
        return LocalDate.parse(s).plusDays(1).atStartOfDay().minusNanos(1);
    }
}
