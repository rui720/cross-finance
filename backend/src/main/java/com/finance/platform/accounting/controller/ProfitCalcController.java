package com.finance.platform.accounting.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finance.platform.accounting.entity.ProfitReport;
import com.finance.platform.accounting.service.ProfitEngineService;
import com.finance.platform.common.core.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 利润计算触发接口
 * <p>
 * 对外提供周期利润核算触发与利润报表分页查询能力。
 * 权限：触发核算仅 ADMIN/FINANCE；查询报表对 ADMIN/FINANCE/OPERATOR 开放（运营只读）。
 */
@Slf4j
@RestController
@RequestMapping("/accounting/profit")
@RequiredArgsConstructor
public class ProfitCalcController {

    private final ProfitEngineService profitEngineService;

    /**
     * 触发指定周期的利润核算
     *
     * @param period 核算周期，如 202607
     */
    @PostMapping("/calculate")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public Result<Void> calculate(@RequestParam String period) {
        log.info("[利润] 触发核算 period={}", period);
        profitEngineService.calculate(period);
        return Result.success();
    }

    /**
     * 分页查询利润报表
     *
     * @param period 核算周期
     * @param page   页码
     * @param size   每页大小
     */
    @GetMapping("/report")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','OPERATOR')")
    public Result<Page<ProfitReport>> report(@RequestParam(required = false) String period,
                                              @RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "10") int size) {
        return Result.success(profitEngineService.getReport(period, page, size));
    }
}

