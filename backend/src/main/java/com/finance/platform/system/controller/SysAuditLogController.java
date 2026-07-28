package com.finance.platform.system.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finance.platform.common.core.Result;
import com.finance.platform.system.entity.SysAuditLog;
import com.finance.platform.system.service.SysAuditLogService;
import com.finance.platform.system.service.UndoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审计日志查询与管理接口
 * <p>
 * 支持按用户名、操作时间范围分页查询，以及批量删除（管理员权限）。
 * 所有接口仅管理员可用。
 */
@Slf4j
@RestController
@RequestMapping("/system/audit")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class SysAuditLogController {

    private final SysAuditLogService sysAuditLogService;
    private final UndoService undoService;

    /**
     * 分页查询审计日志
     *
     * @param username  用户名（模糊查询）
     * @param startTime 操作时间范围-开始
     * @param endTime   操作时间范围-结束
     */
    @GetMapping("/page")
    public Result<Page<SysAuditLog>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        Page<SysAuditLog> p = new Page<>(page, size);
        sysAuditLogService.page(p, new LambdaQueryWrapper<SysAuditLog>()
                .like(StrUtil.isNotBlank(username), SysAuditLog::getUsername, username)
                .ge(startTime != null, SysAuditLog::getCreateTime, startTime)
                .le(endTime != null, SysAuditLog::getCreateTime, endTime)
                .orderByDesc(SysAuditLog::getId));
        return Result.success(p);
    }

    /**
     * 批量删除审计日志（仅管理员使用）
     *
     * @param ids 日志 ID 列表
     */
    @DeleteMapping("/batch")
    public Result<Void> batchDelete(@RequestBody List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Result.success();
        }
        sysAuditLogService.removeByIds(ids);
        log.info("[审计] 批量删除审计日志 count={}", ids.size());
        return Result.success();
    }

    /**
     * 删除单条审计日志
     *
     * @param id 日志 ID
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        sysAuditLogService.removeById(id);
        return Result.success();
    }

    /**
     * 撤销审计日志对应的操作
     * <p>
     * 根据操作类型和 old_value 快照执行对应的撤销逻辑：
     * <ul>
     *   <li>update/状态变更：恢复旧实体</li>
     *   <li>delete：恢复 deleted=0</li>
     *   <li>add/create：逻辑删除新增记录</li>
     *   <li>import：按批次号删除</li>
     *   <li>calculate：按周期删除利润报表</li>
     * </ul>
     * 撤销成功后标记 undone=1，防止重复撤销。
     *
     * @param id 审计日志 ID
     * @return 撤销结果描述
     */
    @PostMapping("/undo/{id}")
    public Result<String> undo(@PathVariable Long id) {
        String desc = undoService.undo(id);
        return Result.success(desc);
    }
}
