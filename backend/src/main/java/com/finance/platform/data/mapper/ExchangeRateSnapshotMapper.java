package com.finance.platform.data.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.finance.platform.data.entity.ExchangeRateSnapshot;
import org.apache.ibatis.annotations.Mapper;

/**
 * 汇率快照数据访问层
 */
@Mapper
public interface ExchangeRateSnapshotMapper extends BaseMapper<ExchangeRateSnapshot> {
}
