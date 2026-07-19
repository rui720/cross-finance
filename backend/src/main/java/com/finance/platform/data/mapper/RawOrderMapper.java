package com.finance.platform.data.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finance.platform.data.entity.RawOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

/**
 * 原始订单数据访问层
 */
@Mapper
public interface RawOrderMapper extends BaseMapper<RawOrder> {

    /**
     * 按 batch_no 分组分页查询导入批次记录
     * 返回字段：batchNo, source, recordCount, importTime, status
     */
    @Select("SELECT batch_no AS batchNo, " +
            "MIN(source) AS source, " +
            "COUNT(*) AS recordCount, " +
            "MIN(create_time) AS importTime, " +
            "'SUCCESS' AS status " +
            "FROM raw_order " +
            "WHERE deleted = 0 AND batch_no IS NOT NULL " +
            "GROUP BY batch_no " +
            "ORDER BY MIN(create_time) DESC")
    IPage<Map<String, Object>> selectBatchPage(Page<Map<String, Object>> page);

    /**
     * 统计导入批次数
     */
    @Select("SELECT COUNT(DISTINCT batch_no) FROM raw_order WHERE deleted = 0 AND batch_no IS NOT NULL")
    long countBatch();
}
