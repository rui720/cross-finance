package com.finance.platform.data.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finance.platform.common.constant.BusinessConstants;
import com.finance.platform.common.core.Result;
import com.finance.platform.data.entity.RawOrder;
import com.finance.platform.data.mapper.RawOrderMapper;
import com.finance.platform.data.service.BankReconcileService;
import com.finance.platform.data.vo.ReconcileResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 银行流水与对账接口
 * <p>
 * 银行流水统一存储于 raw_order 表（source=BANK），提供分页查询与自动对账能力。
 * <p>
 * 对账功能：
 * <ul>
 *   <li>自动对账：按订单号→金额+日期三级策略自动匹配平台账单与银行流水</li>
 *   <li>结果查询：按对账类型（已匹配/金额差异/未到账/不明入账）筛选查看</li>
 *   <li>手动操作：手动匹配、标记差异已处理、取消对账</li>
 * </ul>
 * 权限：查询对 ADMIN/FINANCE/OPERATOR 开放；对账写操作仅 ADMIN/FINANCE。
 */
@Slf4j
@RestController
@RequestMapping("/data/bank-flow")
@RequiredArgsConstructor
public class BankFlowController {

    private final RawOrderMapper rawOrderMapper;
    private final BankReconcileService bankReconcileService;

    /**
     * 分页查询银行流水
     */
    @GetMapping("/page")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','OPERATOR')")
    public Result<Page<RawOrder>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String batchNo,
            @RequestParam(required = false) Integer reconcileStatus) {
        Page<RawOrder> p = new Page<>(page, size);
        rawOrderMapper.selectPage(p, new LambdaQueryWrapper<RawOrder>()
                .eq(RawOrder::getSource, BusinessConstants.SOURCE_BANK)
                .eq(StrUtil.isNotBlank(batchNo), RawOrder::getBatchNo, batchNo)
                .eq(reconcileStatus != null, RawOrder::getReconcileStatus, reconcileStatus)
                .orderByDesc(RawOrder::getId));
        return Result.success(p);
    }

    // ==================== 自动对账接口 ====================

    /**
     * 触发自动对账
     * <p>
     * 在指定日期范围内，自动匹配平台账单与银行流水。
     * 匹配前先重置范围内所有记录的对账状态，再按三级策略重新匹配。
     *
     * @param startDate 起始日期 yyyy-MM-dd（含）
     * @param endDate   结束日期 yyyy-MM-dd（含）
     * @return 对账结果汇总
     */
    @PostMapping("/reconcile/auto")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public Result<BankReconcileService.ReconcileSummary> autoReconcile(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        log.info("[对账] 触发自动对账 range={} ~ {}", startDate, endDate);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        LocalDateTime start = LocalDate.parse(startDate, fmt).atStartOfDay();
        LocalDateTime end = LocalDate.parse(endDate, fmt).atTime(23, 59, 59);
        BankReconcileService.ReconcileSummary summary = bankReconcileService.autoReconcile(start, end);
        return Result.success(summary);
    }

    /**
     * 分页查询对账结果（以平台账单为主体聚合，一行一个订单）
     * <p>
     * 以平台账单为主体，按订单号关联银行流水与中转费，5 个 CNY 金额列集中展示方便比对。
     * <ul>
     *   <li>reconcileStatus != 4：查平台账单，聚合银行流水+中转费</li>
     *   <li>reconcileStatus == 4（不明入账）：查无对应平台账单的银行流水，仅银行到账列有值</li>
     * </ul>
     *
     * @param page            页码
     * @param size            每页条数
     * @param startDate       起始日期
     * @param endDate         结束日期
     * @param reconcileStatus 对账状态筛选：0未对账/1已完成/2对账失败/3未到账/4不明入账
     */
    @GetMapping("/reconcile/result")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','OPERATOR')")
    public Result<Page<ReconcileResultVO>> reconcileResult(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) Integer reconcileStatus,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String orderNo) {
        // 日期范围解析（final 以便 lambda 引用）
        final LocalDateTime start;
        final LocalDateTime end;
        if (StrUtil.isNotBlank(startDate) && StrUtil.isNotBlank(endDate)) {
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            start = LocalDate.parse(startDate, fmt).atStartOfDay();
            end = LocalDate.parse(endDate, fmt).atTime(23, 59, 59);
        } else {
            start = null;
            end = null;
        }

        // 日期/筛选条件构造器（平台用 orderTime，银行用 settleTime）
        // 平台/币种/订单号筛选同时应用于平台账单和银行流水
        java.util.function.Consumer< LambdaQueryWrapper<RawOrder>> applyCommonFilters = w -> {
            if (StrUtil.isNotBlank(platform)) w.eq(RawOrder::getPlatform, platform);
            if (StrUtil.isNotBlank(currency)) w.eq(RawOrder::getCurrency, currency);
            if (StrUtil.isNotBlank(orderNo)) w.like(RawOrder::getOrderNo, orderNo);
        };

        // 单边数据呈现逻辑：
        // - status=3（未到账）：只有账单无流水，以平台账单为主体展示
        // - status=4（不明入账）：只有流水无账单，以银行流水为主体展示
        // - 默认（不选 status）：合并平台账单 + 不明入账的银行流水，让两类单边数据都能看到
        //   为避免分页错乱，采用"两段查询合并"策略：先查平台账单，若当前页未填满再补查不明银行流水
        boolean fromBank = reconcileStatus != null && reconcileStatus == RawOrder.RECONCILE_UNKNOWN;

        if (fromBank) {
            // 仅查不明入账（银行流水为主体）
            Page<RawOrder> p = new Page<>(page, size);
            LambdaQueryWrapper<RawOrder> wrapper = new LambdaQueryWrapper<RawOrder>()
                    .eq(RawOrder::getSource, BusinessConstants.SOURCE_BANK)
                    .eq(RawOrder::getCleanStatus, RawOrder.CLEAN_STATUS_DONE)
                    .eq(RawOrder::getReconcileStatus, RawOrder.RECONCILE_UNKNOWN)
                    .orderByDesc(RawOrder::getId);
            applyCommonFilters.accept(wrapper);
            if (start != null && end != null) {
                wrapper.and(w -> w
                        .ge(RawOrder::getSettleTime, start).le(RawOrder::getSettleTime, end));
            }
            rawOrderMapper.selectPage(p, wrapper);
            Page<ReconcileResultVO> result = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
            result.setRecords(p.getRecords().stream()
                    .map(bankReconcileService::buildReconcileResultFromBank).toList());
            return Result.success(result);
        }

        // 平台账单为主体查询
        Page<RawOrder> p = new Page<>(page, size);
        LambdaQueryWrapper<RawOrder> wrapper = new LambdaQueryWrapper<RawOrder>()
                .eq(RawOrder::getSource, BusinessConstants.SOURCE_PLATFORM)
                .eq(RawOrder::getCleanStatus, RawOrder.CLEAN_STATUS_DONE)
                .eq(reconcileStatus != null, RawOrder::getReconcileStatus, reconcileStatus)
                .orderByDesc(RawOrder::getReconcileStatus)
                .orderByDesc(RawOrder::getId);
        applyCommonFilters.accept(wrapper);
        if (start != null && end != null) {
            wrapper.and(w -> w
                    .ge(RawOrder::getOrderTime, start).le(RawOrder::getOrderTime, end));
        }
        rawOrderMapper.selectPage(p, wrapper);

        // 默认查询（不选 status）时，若平台账单未填满当前页，补充不明入账的银行流水
        List<ReconcileResultVO> records = new java.util.ArrayList<>(p.getRecords().stream()
                .map(bankReconcileService::buildReconcileResult).toList());

        if (reconcileStatus == null && records.size() < size) {
            int need = (int) size - records.size();
            LambdaQueryWrapper<RawOrder> bankWrapper = new LambdaQueryWrapper<RawOrder>()
                    .eq(RawOrder::getSource, BusinessConstants.SOURCE_BANK)
                    .eq(RawOrder::getCleanStatus, RawOrder.CLEAN_STATUS_DONE)
                    .eq(RawOrder::getReconcileStatus, RawOrder.RECONCILE_UNKNOWN)
                    .orderByDesc(RawOrder::getId);
            applyCommonFilters.accept(bankWrapper);
            if (start != null && end != null) {
                bankWrapper.and(w -> w
                        .ge(RawOrder::getSettleTime, start).le(RawOrder::getSettleTime, end));
            }
            bankWrapper.last("LIMIT " + need);
            List<RawOrder> bankOnly = rawOrderMapper.selectList(bankWrapper);
            records.addAll(bankOnly.stream()
                    .map(bankReconcileService::buildReconcileResultFromBank).toList());
        }

        // 合并总数 = 平台账单总数 + 不明入账银行流水数（默认查询时）
        long total = p.getTotal();
        if (reconcileStatus == null) {
            LambdaQueryWrapper<RawOrder> bankCount = new LambdaQueryWrapper<RawOrder>()
                    .eq(RawOrder::getSource, BusinessConstants.SOURCE_BANK)
                    .eq(RawOrder::getCleanStatus, RawOrder.CLEAN_STATUS_DONE)
                    .eq(RawOrder::getReconcileStatus, RawOrder.RECONCILE_UNKNOWN);
            applyCommonFilters.accept(bankCount);
            if (start != null && end != null) {
                bankCount.and(w -> w
                        .ge(RawOrder::getSettleTime, start).le(RawOrder::getSettleTime, end));
            }
            total += rawOrderMapper.selectCount(bankCount);
        }

        Page<ReconcileResultVO> result = new Page<>(p.getCurrent(), p.getSize(), total);
        result.setRecords(records);
        return Result.success(result);
    }

    /**
     * 对账结果汇总统计
     *
     * @param startDate 起始日期
     * @param endDate   结束日期
     * @return 各类型数量统计
     */
    @GetMapping("/reconcile/summary")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','OPERATOR')")
    public Result<Map<String, Object>> reconcileSummary(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        LambdaQueryWrapper<RawOrder> wrapper = new LambdaQueryWrapper<RawOrder>()
                .in(RawOrder::getSource, BusinessConstants.SOURCE_PLATFORM, BusinessConstants.SOURCE_BANK)
                .eq(RawOrder::getCleanStatus, RawOrder.CLEAN_STATUS_DONE);

        if (StrUtil.isNotBlank(startDate) && StrUtil.isNotBlank(endDate)) {
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            LocalDateTime start = LocalDate.parse(startDate, fmt).atStartOfDay();
            LocalDateTime end = LocalDate.parse(endDate, fmt).atTime(23, 59, 59);
            wrapper.and(w -> w
                    .and(w1 -> w1.eq(RawOrder::getSource, BusinessConstants.SOURCE_PLATFORM)
                            .ge(RawOrder::getOrderTime, start).le(RawOrder::getOrderTime, end))
                    .or(w2 -> w2.eq(RawOrder::getSource, BusinessConstants.SOURCE_BANK)
                            .ge(RawOrder::getSettleTime, start).le(RawOrder::getSettleTime, end)));
        }

        List<RawOrder> all = rawOrderMapper.selectList(wrapper);
        long matched = all.stream().filter(o -> o.getReconcileStatus() != null && o.getReconcileStatus() == RawOrder.RECONCILE_DONE).count();
        long amountDiff = all.stream().filter(o -> o.getReconcileStatus() != null && o.getReconcileStatus() == RawOrder.RECONCILE_FAIL).count();
        long platformOnly = all.stream().filter(o -> o.getReconcileStatus() != null && o.getReconcileStatus() == RawOrder.RECONCILE_UNRECEIVED).count();
        long bankOnly = all.stream().filter(o -> o.getReconcileStatus() != null && o.getReconcileStatus() == RawOrder.RECONCILE_UNKNOWN).count();
        long pending = all.stream().filter(o -> o.getReconcileStatus() == null || o.getReconcileStatus() == RawOrder.RECONCILE_NONE).count();

        return Result.success(Map.of(
                "total", all.size(),
                "matched", matched,
                "amountDiff", amountDiff,
                "platformOnly", platformOnly,
                "bankOnly", bankOnly,
                "pending", pending
        ));
    }

    /**
     * 手动匹配：用户指定一条平台订单和一条银行流水进行匹配
     *
     * @param body 包含 platformOrderId 和 bankFlowId
     */
    @PostMapping("/reconcile/manual")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public Result<Void> manualMatch(@RequestBody Map<String, Long> body) {
        Long platformOrderId = body.get("platformOrderId");
        Long bankFlowId = body.get("bankFlowId");
        if (platformOrderId == null || bankFlowId == null) {
            return Result.error("请指定平台订单ID和银行流水ID");
        }
        bankReconcileService.manualMatch(platformOrderId, bankFlowId);
        return Result.success();
    }

    /**
     * 标记差异已处理（用户确认差异项已解决，状态改为已对账）
     *
     * @param ids 记录ID列表
     */
    @PostMapping("/reconcile/resolve")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public Result<Void> resolveDiff(@RequestBody List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Result.success();
        }
        bankReconcileService.resolveDiff(ids);
        return Result.success();
    }

    /**
     * 取消对账：重置为未对账状态
     *
     * @param ids 记录ID列表
     */
    @PostMapping("/reconcile/cancel")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public Result<Void> cancelReconcile(@RequestBody List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Result.success();
        }
        bankReconcileService.cancelReconcile(ids);
        return Result.success();
    }
}
