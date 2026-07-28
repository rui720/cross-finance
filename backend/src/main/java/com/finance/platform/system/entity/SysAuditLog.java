package com.finance.platform.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.finance.platform.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 系统审计日志实体
 * <p>
 * 记录用户操作行为、操作前数据快照及执行结果，用于安全审计与撤销恢复。
 * 已去除技术性字段（method/params/ip/cost_time），仅保留业务可读信息。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_audit_log")
public class SysAuditLog extends BaseEntity {

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作用户 ID */
    @TableField("user_id")
    private Long userId;

    /** 操作用户名 */
    @TableField("username")
    private String username;

    /** 操作描述 */
    @TableField("operation")
    private String operation;

    /** 操作前数据快照（JSON，用于撤销恢复） */
    @TableField("old_value")
    private String oldValue;

    /** 状态：0 失败，1 成功 */
    @TableField("status")
    private Integer status;

    /** 是否已撤销：0 未撤销，1 已撤销 */
    @TableField("undone")
    private Integer undone;

    /** 错误信息 */
    @TableField("error_msg")
    private String errorMsg;
}
