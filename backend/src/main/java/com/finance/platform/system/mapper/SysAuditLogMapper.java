package com.finance.platform.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.finance.platform.system.entity.SysAuditLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 审计日志数据访问层
 */
@Mapper
public interface SysAuditLogMapper extends BaseMapper<SysAuditLog> {
}
