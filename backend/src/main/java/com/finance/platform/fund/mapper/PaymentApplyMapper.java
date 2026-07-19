package com.finance.platform.fund.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.finance.platform.fund.entity.PaymentApply;
import org.apache.ibatis.annotations.Mapper;

/**
 * 付款申请单数据访问层
 */
@Mapper
public interface PaymentApplyMapper extends BaseMapper<PaymentApply> {
}
