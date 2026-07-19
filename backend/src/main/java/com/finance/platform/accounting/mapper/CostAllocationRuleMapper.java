package com.finance.platform.accounting.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.finance.platform.accounting.entity.CostAllocationRule;
import org.apache.ibatis.annotations.Mapper;

/**
 * 费用分摊规则数据访问层
 */
@Mapper
public interface CostAllocationRuleMapper extends BaseMapper<CostAllocationRule> {
}
