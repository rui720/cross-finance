package com.finance.platform.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.finance.platform.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 部门实体
 * <p>
 * 支持父子层级（parent_id），用于用户归属部门展示与数据范围过滤。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_dept")
public class SysDept extends BaseEntity {

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 父部门 ID，0 表示顶级 */
    @TableField("parent_id")
    private Long parentId;

    /** 部门名称 */
    @TableField("dept_name")
    private String deptName;

    /** 部门编码 */
    @TableField("dept_code")
    private String deptCode;

    /** 排序（升序） */
    @TableField("sort")
    private Integer sort;

    /** 状态：0 禁用，1 启用 */
    @TableField("status")
    private Integer status;
}
