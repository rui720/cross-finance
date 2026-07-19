package com.finance.platform.accounting.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.finance.platform.accounting.entity.ProfitReport;
import org.apache.ibatis.annotations.Mapper;

/**
 * 利润报表数据访问层
 */
@Mapper
public interface ProfitReportMapper extends BaseMapper<ProfitReport> {
}
