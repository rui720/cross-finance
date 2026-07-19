package com.finance.platform.fund.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.finance.platform.fund.entity.BudgetPlan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

/**
 * 预算计划数据访问层
 */
@Mapper
public interface BudgetPlanMapper extends BaseMapper<BudgetPlan> {

    /**
     * 原子扣减预算：used_amount = used_amount + delta，且校验不超支
     * <p>
     * 通过 WHERE used_amount + delta <= total_amount 保证并发安全，
     * 返回影响行数：1=扣减成功，0=超支失败。
     *
     * @param id    预算计划 ID
     * @param delta 增量金额（正数）
     * @return 影响行数
     */
    @Update("UPDATE budget_plan SET used_amount = used_amount + #{delta} " +
            "WHERE id = #{id} AND used_amount + #{delta} <= total_amount")
    int atomicDeduct(@Param("id") Long id, @Param("delta") BigDecimal delta);
}
