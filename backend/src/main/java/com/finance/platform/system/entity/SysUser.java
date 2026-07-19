package com.finance.platform.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.finance.platform.common.core.BaseEntity;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 系统用户实体
 * <p>
 * 存储账号、密码、联系方式、状态及角色关联信息；密码字段不序列化到前端。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUser extends BaseEntity {

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户名 */
    @NotBlank(message = "用户名不能为空")
    @TableField("username")
    private String username;

    /** 密码（BCrypt 加密存储，不返回前端） */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @TableField("password")
    private String password;

    /** 真实姓名 */
    @TableField("real_name")
    private String realName;

    /** 手机号 */
    @TableField("phone")
    private String phone;

    /** 邮箱 */
    @TableField("email")
    private String email;

    /** 状态：0 禁用，1 启用 */
    @TableField("status")
    private Integer status;

    /** 部门 ID */
    @TableField("dept_id")
    private Long deptId;

    /** 角色 ID 列表（存 JSON 字符串） */
    @TableField("role_ids")
    private String roleIds;
}
