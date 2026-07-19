package com.finance.platform.accounting.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.finance.platform.accounting.entity.ProfitReport;

/**
 * 利润引擎服务接口
 * <p>
 * 触发周期利润核算并对外提供利润报表分页查询能力。
 */
public interface ProfitEngineService extends IService<ProfitReport> {

    /**
     * 触发指定周期的利润核算
     *
     * @param period 核算周期，如 202607
     */
    void calculate(String period);

    /**
     * 分页查询利润报表
     *
     * @param period 核算周期
     * @param page   页码
     * @param size   每页大小
     * @return 利润报表分页数据
     */
    Page<ProfitReport> getReport(String period, int page, int size);
}
