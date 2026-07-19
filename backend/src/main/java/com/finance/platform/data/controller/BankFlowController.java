package com.finance.platform.data.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finance.platform.common.constant.BusinessConstants;
import com.finance.platform.common.core.Result;
import com.finance.platform.data.entity.RawOrder;
import com.finance.platform.data.mapper.RawOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 银行流水查询接口
 * <p>
 * 银行流水统一存储于 raw_order 表（source=BANK），提供分页查询与对账状态查询。
 * 权限：查询对 ADMIN/FINANCE/OPERATOR 开放；对账写操作仅 ADMIN/FINANCE。
 */
@Slf4j
@RestController
@RequestMapping("/data/bank-flow")
@RequiredArgsConstructor
public class BankFlowController {

    private final RawOrderMapper rawOrderMapper;

    /**
     * 分页查询银行流水
     */
    @GetMapping("/page")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','OPERATOR')")
    public Result<Page<RawOrder>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String batchNo) {
        Page<RawOrder> p = new Page<>(page, size);
        rawOrderMapper.selectPage(p, new LambdaQueryWrapper<RawOrder>()
                .eq(RawOrder::getSource, BusinessConstants.SOURCE_BANK)
                .eq(StrUtil.isNotBlank(batchNo), RawOrder::getBatchNo, batchNo)
                .orderByDesc(RawOrder::getId));
        return Result.success(p);
    }

    /**
     * 批量对账：将指定流水标记为已对账（此处用 settle_time 落库表示已对账）
     *
     * @param ids 流水 ID 列表
     */
    @PostMapping("/reconcile")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public Result<Void> reconcile(@RequestBody List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Result.success();
        }
        ids.forEach(id -> {
            RawOrder update = new RawOrder();
            update.setId(id);
            update.setSettleTime(java.time.LocalDateTime.now());
            rawOrderMapper.updateById(update);
        });
        log.info("[对账] 批量对账完成 count={}", ids.size());
        return Result.success();
    }

    /**
     * 对账状态查询
     *
     * @param batchNo 批次号
     * @return 对账状态汇总
     */
    @GetMapping("/reconcile/status")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','OPERATOR')")
    public Result<Map<String, Object>> reconcileStatus(@RequestParam String batchNo) {
        Long total = rawOrderMapper.selectCount(new LambdaQueryWrapper<RawOrder>()
                .eq(RawOrder::getBatchNo, batchNo)
                .eq(RawOrder::getSource, BusinessConstants.SOURCE_BANK));
        Map<String, Object> status = new HashMap<>();
        status.put("batchNo", batchNo);
        status.put("total", total);
        status.put("status", total > 0 ? "已导入" : "无数据");
        return Result.success(status);
    }
}
