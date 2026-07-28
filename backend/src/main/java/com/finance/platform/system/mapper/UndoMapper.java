package com.finance.platform.system.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 撤销操作专用 Mapper
 * <p>
 * 使用原始 SQL 直接操作 deleted 字段，绕过 MyBatis-Plus 逻辑删除过滤，
 * 实现已删除数据的恢复（deleted=1 → deleted=0）。
 */
@Mapper
public interface UndoMapper {

    /** 恢复逻辑删除的账单/银行流水记录 */
    @Update("UPDATE raw_order SET deleted = 0 WHERE id = #{id}")
    int restoreOrder(@Param("id") Long id);

    /** 恢复逻辑删除的额外费用记录 */
    @Update("UPDATE extra_cost SET deleted = 0 WHERE id = #{id}")
    int restoreCost(@Param("id") Long id);

    /** 恢复逻辑删除的汇率记录 */
    @Update("UPDATE exchange_rate_snapshot SET deleted = 0 WHERE id = #{id}")
    int restoreRate(@Param("id") Long id);

    /** 恢复逻辑删除的用户记录 */
    @Update("UPDATE sys_user SET deleted = 0 WHERE id = #{id}")
    int restoreUser(@Param("id") Long id);

    /** 恢复逻辑删除的审计日志记录 */
    @Update("UPDATE sys_audit_log SET deleted = 0 WHERE id = #{id}")
    int restoreAuditLog(@Param("id") Long id);

    /** 逻辑删除新增的用户记录（撤销新增用户用） */
    @Update("UPDATE sys_user SET deleted = 1 WHERE id = #{id}")
    int softDeleteUser(@Param("id") Long id);

    /** 逻辑删除指定批次的所有账单数据（撤销导入用） */
    @Update("UPDATE raw_order SET deleted = 1 WHERE batch_no = #{batchNo}")
    int deleteByBatchNo(@Param("batchNo") String batchNo);

    /** 逻辑删除指定周期的利润报表（撤销核算用） */
    @Update("UPDATE profit_report SET deleted = 1 WHERE period = #{period}")
    int deleteProfitReportByPeriod(@Param("period") String period);
}
