package com.finance.platform.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.finance.platform.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AI 顾问会话实体
 * <p>
 * 一个用户可拥有多个会话，每个会话独立维护多轮消息上下文。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_session")
public class AiSession extends BaseEntity {

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属用户 ID（关联 sys_user.id） */
    @TableField("user_id")
    private Long userId;

    /** 会话标题 */
    @TableField("title")
    private String title;
}
