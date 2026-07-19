package com.finance.platform.system.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.finance.platform.system.entity.SysAuditLog;
import com.finance.platform.system.mapper.SysAuditLogMapper;
import com.finance.platform.system.service.SysAuditLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 审计日志服务实现
 * <p>
 * 通过 etlExecutor 线程池异步落库，避免阻塞业务主流程。
 */
@Slf4j
@Service
public class AuditLogServiceImpl extends ServiceImpl<SysAuditLogMapper, SysAuditLog> implements SysAuditLogService {

    @Async("etlExecutor")
    @Override
    public void asyncSave(SysAuditLog log) {
        save(log);
    }
}
