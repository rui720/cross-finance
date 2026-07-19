package com.finance.platform.ai.service;

import com.finance.platform.ai.entity.AiMessage;
import com.finance.platform.ai.entity.AiSession;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * AI 顾问对话服务接口
 * <p>
 * 提供会话持久化、多轮上下文记忆与 SSE 真实流式输出能力。
 * 支持会话的增删改查、消息列表查询、流式发送、消息编辑与删除。
 */
public interface AiChatService {

    /**
     * 创建会话
     *
     * @param userId 当前登录用户 ID
     * @param title  会话标题，为空时默认"新对话"
     * @return 新建的会话实体
     */
    AiSession createSession(Long userId, String title);

    /**
     * 查询指定用户的会话列表（按 update_time 倒序）
     *
     * @param userId 用户 ID
     * @return 会话列表
     */
    List<AiSession> listSessions(Long userId);

    /**
     * 重命名会话
     *
     * @param sessionId 会话 ID
     * @param title     新标题
     */
    void renameSession(Long sessionId, String title);

    /**
     * 删除会话（逻辑删除）
     *
     * @param sessionId 会话 ID
     */
    void deleteSession(Long sessionId);

    /**
     * 查询会话内的消息列表（按 seq_no 正序）
     *
     * @param sessionId 会话 ID
     * @return 消息列表
     */
    List<AiMessage> listMessages(Long sessionId);

    /**
     * 流式发送消息：保存用户消息，结合最近 10 条历史构建上下文，
     * 通过 SSE 逐 token 推送 AI 回复，完成后保存 AI 消息。
     *
     * @param userId    当前登录用户 ID
     * @param sessionId 会话 ID
     * @param content   用户消息内容
     * @return SseEmitter 流式响应
     */
    SseEmitter sendMessage(Long userId, Long sessionId, String content);

    /**
     * 修改消息内容，并删除该消息之后的所有消息（重新生成分支）
     *
     * @param messageId 消息 ID
     * @param content   新内容
     * @return 所属会话 ID
     */
    Long editMessage(Long messageId, String content);

    /**
     * 删除单条消息（逻辑删除）
     *
     * @param messageId 消息 ID
     */
    void deleteMessage(Long messageId);

    /**
     * 重新生成 AI 回复（不保存用户消息，基于现有上下文生成）
     * 用于编辑用户消息后重新获取 AI 回复，避免重复保存 USER 消息
     *
     * @param userId    当前登录用户 ID
     * @param sessionId 会话 ID
     * @return SseEmitter 流式响应
     */
    SseEmitter regenerate(Long userId, Long sessionId);
}
