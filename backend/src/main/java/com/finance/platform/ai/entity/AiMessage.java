package com.finance.platform.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.finance.platform.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AI 顾问消息实体
 * <p>
 * 记录会话内的每一条消息（用户提问或 AI 回复），按 seq_no 维护多轮顺序。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_message")
public class AiMessage extends BaseEntity {

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属会话 ID（关联 ai_session.id） */
    @TableField("session_id")
    private Long sessionId;

    /** 消息角色：USER / ASSISTANT */
    @TableField("role")
    private String role;

    /** 消息内容 */
    @TableField("content")
    private String content;

    /** 消息序号（会话内自增） */
    @TableField("seq_no")
    private Integer seqNo;
}
