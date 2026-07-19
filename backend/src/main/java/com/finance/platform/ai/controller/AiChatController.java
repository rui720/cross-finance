package com.finance.platform.ai.controller;

import com.finance.platform.ai.entity.AiMessage;
import com.finance.platform.ai.entity.AiSession;
import com.finance.platform.ai.service.AiChatService;
import com.finance.platform.common.core.LoginUser;
import com.finance.platform.common.core.Result;
import com.finance.platform.common.exception.BusinessException;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * AI 合规顾问对话接口
 * <p>
 * 提供会话持久化、多轮上下文记忆与 SSE 真实流式输出能力。
 * 会话管理：/ai/session/**；消息管理：/ai/message/**；流式发送：/ai/chat/send。
 * 兼容旧接口：/ai/chat/ask（已废弃）、/ai/chat/stream（旧非真实流式）。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AiChatController {

    private final ChatLanguageModel chatLanguageModel;
    private final AiChatService aiChatService;

    // ==================== 会话管理 ====================

    /**
     * 创建会话
     * 请求体：{ "title": "xxx" }
     */
    @PostMapping("/ai/session/create")
    public Result<AiSession> createSession(@RequestBody Map<String, String> body) {
        return Result.success(aiChatService.createSession(currentUserId(), body.get("title")));
    }

    /**
     * 查询当前用户的会话列表（按 update_time 倒序）
     */
    @GetMapping("/ai/session/list")
    public Result<List<AiSession>> listSessions() {
        return Result.success(aiChatService.listSessions(currentUserId()));
    }

    /**
     * 重命名会话
     * 请求体：{ "id": 1, "title": "xxx" }
     */
    @PutMapping("/ai/session/rename")
    public Result<Void> renameSession(@RequestBody Map<String, Object> body) {
        aiChatService.renameSession(toLong(body.get("id")), (String) body.get("title"));
        return Result.success();
    }

    /**
     * 删除会话（逻辑删除，级联删除会话下消息）
     */
    @DeleteMapping("/ai/session/{id}")
    public Result<Void> deleteSession(@PathVariable Long id) {
        aiChatService.deleteSession(id);
        return Result.success();
    }

    // ==================== 消息管理 ====================

    /**
     * 查询会话内消息列表（按 seq_no 正序）
     */
    @GetMapping("/ai/message/list/{sessionId}")
    public Result<List<AiMessage>> listMessages(@PathVariable Long sessionId) {
        return Result.success(aiChatService.listMessages(sessionId));
    }

    /**
     * 流式发送消息（SSE 真实流式）
     * 请求体：{ "sessionId": 1, "content": "xxx" }
     * 响应：text/event-stream，事件数据为 JSON：
     *   {"type":"token","content":"xxx"} / {"type":"done","messageId":xxx} / {"type":"error","message":"xxx"}
     */
    @PostMapping(value = "/ai/chat/send", produces = "text/event-stream")
    public SseEmitter send(@RequestBody Map<String, Object> body) {
        Long sessionId = toLong(body.get("sessionId"));
        String content = (String) body.get("content");
        if (content == null || content.isBlank()) {
            throw new BusinessException(400, "消息内容不能为空");
        }
        return aiChatService.sendMessage(currentUserId(), sessionId, content);
    }

    /**
     * 重新生成 AI 回复（SSE 真实流式）
     * 用于编辑用户消息后重新获取 AI 回复，不保存用户消息
     * 请求体：{ "sessionId": 1 }
     */
    @PostMapping(value = "/ai/chat/regenerate", produces = "text/event-stream")
    public SseEmitter regenerate(@RequestBody Map<String, Object> body) {
        Long sessionId = toLong(body.get("sessionId"));
        return aiChatService.regenerate(currentUserId(), sessionId);
    }

    /**
     * 修改消息内容（删除其后所有消息，用于重新生成分支）
     * 请求体：{ "messageId": 1, "content": "xxx" }
     * 返回：{ "sessionId": 1 }
     */
    @PutMapping("/ai/message/edit")
    public Result<Map<String, Object>> editMessage(@RequestBody Map<String, Object> body) {
        Long messageId = toLong(body.get("messageId"));
        String content = (String) body.get("content");
        Long sessionId = aiChatService.editMessage(messageId, content);
        return Result.success(Map.of("sessionId", sessionId));
    }

    /**
     * 删除单条消息（逻辑删除）
     */
    @DeleteMapping("/ai/message/{id}")
    public Result<Void> deleteMessage(@PathVariable Long id) {
        aiChatService.deleteMessage(id);
        return Result.success();
    }

    // ==================== 兼容旧接口（向后兼容） ====================

    /**
     * 流式问答（SSE，非真实流式，调用模型后一次性推送）
     */
    @GetMapping("/ai/chat/stream")
    public SseEmitter stream(@RequestParam String question) {
        SseEmitter emitter = new SseEmitter(60000L);
        CompletableFuture.runAsync(() -> {
            try {
                String answer = chatLanguageModel.generate(question);
                emitter.send(SseEmitter.event().name("message").data(answer));
                emitter.complete();
            } catch (Exception e) {
                log.error("[AI] 流式问答失败 question={}", question, e);
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    /**
     * 普通问答（非流式）
     * 请求体：{ "question": "xxx" }
     */
    @Deprecated
    @PostMapping("/ai/chat/ask")
    public Result<String> ask(@RequestBody Map<String, String> body) {
        String question = body.get("question");
        if (question == null || question.isBlank()) {
            return Result.error(400, "问题不能为空");
        }
        log.info("[AI] 收到提问：{}", question);
        try {
            String answer = CompletableFuture
                    .supplyAsync(() -> chatLanguageModel.generate(question))
                    .get(30, TimeUnit.SECONDS);
            return Result.success(answer);
        } catch (TimeoutException e) {
            log.warn("[AI] 问答超时 question={}", question);
            throw new BusinessException("AI 响应超时，请稍后重试");
        } catch (Exception e) {
            log.error("[AI] 问答失败 question={}", question, e);
            throw new BusinessException("AI 服务异常: " + e.getMessage());
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 从 SecurityContext 提取当前登录用户 ID
     */
    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() != null) {
            Object principal = auth.getPrincipal();
            if (principal instanceof LoginUser loginUser) {
                return loginUser.userId();
            }
            if (principal instanceof Long id) {
                return id;
            }
        }
        throw new BusinessException(401, "未登录或登录已过期");
    }

    /**
     * 将请求体中的值转为 Long（兼容 Integer/Long/String）
     */
    private Long toLong(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof Number n) {
            return n.longValue();
        }
        return Long.valueOf(obj.toString());
    }
}
