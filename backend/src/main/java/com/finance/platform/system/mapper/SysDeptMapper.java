package com.finance.platform.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.finance.platform.system.entity.SysDept;
import org.apache.ibatis.annotations.Mapper;

/**
 * 部门数据访问层
 */
@Mapper
public interface SysDeptMapper extends BaseMapper<SysDept> {
}
