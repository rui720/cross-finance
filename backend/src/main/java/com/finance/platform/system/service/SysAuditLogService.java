package com.finance.platform.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.finance.platform.system.entity.SysAuditLog;

/**
 * 审计日志服务接口
 */
public interface SysAuditLogService extends IService<SysAuditLog> {

    /**
     * 异步保存审计日志
     *
     * @param log 审计日志对象
     */
    void asyncSave(SysAuditLog log);
}
