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
 * 记录用户操作行为、请求方法、参数、耗时及执行结果，用于安全审计与问题排查。
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

    /** 请求方法 */
    @TableField("method")
    private String method;

    /** 请求参数 */
    @TableField("params")
    private String params;

    /** 请求 IP */
    @TableField("ip")
    private String ip;

    /** 耗时（ms） */
    @TableField("cost_time")
    private Long costTime;

    /** 状态：0 失败，1 成功 */
    @TableField("status")
    private Integer status;

    /** 错误信息 */
    @TableField("error_msg")
    private String errorMsg;
}
