package com.finance.platform.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.platform.ai.agent.AiTools;
import com.finance.platform.ai.entity.AiMessage;
import com.finance.platform.ai.entity.AiSession;
import com.finance.platform.ai.mapper.AiMessageMapper;
import com.finance.platform.ai.mapper.AiSessionMapper;
import com.finance.platform.ai.service.AiChatService;
import com.finance.platform.common.exception.BusinessException;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 顾问对话服务实现
 * <p>
 * 基于 ai_session / ai_message 持久化会话，结合最近 10 条历史消息构建多轮上下文，
 * 通过 LangChain4j StreamingChatLanguageModel 逐 token 推送 SSE 流式响应。
 * <p>
 * 支持 Agent 工具调用：模型可主动调用 {@link AiTools} 中暴露的查询工具获取实时数据，
 * 工具调用阶段使用非流式模型，最终文本回答使用流式模型推送。
 * <p>
 * SSE 事件数据为 JSON 字符串：
 * - token: {"type":"token","content":"xxx"}      逐 token 推送
 * - tool:  {"type":"tool","name":"xxx"}           工具调用通知（前端可显示"正在查询..."）
 * - done:  {"type":"done","messageId":xxx}        生成完成
 * - error: {"type":"error","message":"xxx"}       错误
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatServiceImpl implements AiChatService {

    private final AiSessionMapper aiSessionMapper;
    private final AiMessageMapper aiMessageMapper;
    private final StreamingChatLanguageModel streamingChatLanguageModel;
    private final ChatLanguageModel chatLanguageModel;
    private final ObjectMapper objectMapper;
    private final AiTools aiTools;

    /** 系统人设：定义 AI 顾问角色与回答风格 */
    private static final String SYSTEM_PROMPT = "你是跨境金融平台的 AI 合规顾问，"
            + "精通跨境支付、外汇结算、业财核算与合规风控。"
            + "当用户询问实时数据（如汇率、订单、利润、预算、付款状态）时，"
            + "请主动调用对应工具获取最新数据，基于真实数据回答。"
            + "请基于历史对话上下文，用专业、准确、简洁的中文回答用户问题。";

    /** 作为上下文的历史消息条数 */
    private static final int CONTEXT_WINDOW = 10;

    /** SSE 超时时间（毫秒） */
    private static final long SSE_TIMEOUT = 120_000L;

    /** 工具调用最大循环次数（防止死循环） */
    private static final int MAX_TOOL_ROUNDS = 5;

    /** 工具规格列表（@PostConstruct 时从 AiTools 提取） */
    private List<ToolSpecification> toolSpecifications;

    /** 工具执行器缓存：toolName -> ToolExecutor（@PostConstruct 时构建） */
    private Map<String, ToolExecutor> toolExecutors;

    /**
     * Bean 初始化后构建工具规格与执行器映射
     */
    @PostConstruct
    public void initTools() {
        this.toolSpecifications = ToolSpecifications.toolSpecificationsFrom(aiTools);
        this.toolExecutors = new ConcurrentHashMap<>();
        for (Method method : aiTools.getClass().getDeclaredMethods()) {
            if (method.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class)) {
                method.setAccessible(true);
                toolExecutors.put(method.getName(), new DefaultToolExecutor(aiTools, method));
            }
        }
        log.info("[AI] Agent 工具初始化完成，共 {} 个工具：{}",
                toolExecutors.size(), toolExecutors.keySet());
    }

    @Override
    public AiSession createSession(Long userId, String title) {
        AiSession session = new AiSession();
        session.setUserId(userId);
        session.setTitle(title == null || title.isBlank() ? "新对话" : title);
        aiSessionMapper.insert(session);
        return session;
    }

    @Override
    public List<AiSession> listSessions(Long userId) {
        return aiSessionMapper.selectList(new LambdaQueryWrapper<AiSession>()
                .eq(AiSession::getUserId, userId)
                .orderByDesc(AiSession::getUpdateTime));
    }

    @Override
    public void renameSession(Long sessionId, String title) {
        AiSession session = new AiSession();
        session.setId(sessionId);
        session.setTitle(title);
        aiSessionMapper.updateById(session);
    }

    @Override
    public void deleteSession(Long sessionId) {
        aiSessionMapper.deleteById(sessionId);
        aiMessageMapper.delete(new LambdaQueryWrapper<AiMessage>()
                .eq(AiMessage::getSessionId, sessionId));
    }

    @Override
    public List<AiMessage> listMessages(Long sessionId) {
        return aiMessageMapper.selectList(new LambdaQueryWrapper<AiMessage>()
                .eq(AiMessage::getSessionId, sessionId)
                .orderByAsc(AiMessage::getSeqNo));
    }

    @Override
    public SseEmitter sendMessage(Long userId, Long sessionId, String content) {
        AiSession session = aiSessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BusinessException(403, "会话不存在或无权访问");
        }

        // 1. 保存用户消息
        int userSeqNo = nextSeqNo(sessionId);
        AiMessage userMsg = new AiMessage();
        userMsg.setSessionId(sessionId);
        userMsg.setRole("USER");
        userMsg.setContent(content);
        userMsg.setSeqNo(userSeqNo);
        aiMessageMapper.insert(userMsg);

        // 2. 查询历史上下文
        List<AiMessage> recent = aiMessageMapper.selectList(new LambdaQueryWrapper<AiMessage>()
                .eq(AiMessage::getSessionId, sessionId)
                .orderByDesc(AiMessage::getSeqNo)
                .last("LIMIT " + CONTEXT_WINDOW));
        Collections.reverse(recent);

        // 3. 构建 ChatMessage 列表（系统人设 + 历史多轮对话）
        List<ChatMessage> chatMessages = buildChatMessages(recent);

        // 4. 创建 SseEmitter，异步执行"工具调用循环 + 流式生成"
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        int aiSeqNo = userSeqNo + 1;

        CompletableFuture.runAsync(() -> {
            try {
                executeWithTools(chatMessages, emitter, aiSeqNo, sessionId);
            } catch (Exception e) {
                log.error("[AI] 对话生成失败 sessionId={}", sessionId, e);
                sendSseEvent(emitter, "error", "message", e.getMessage() == null ? "生成失败" : e.getMessage());
                emitter.complete();
            }
        });

        emitter.onTimeout(emitter::complete);
        emitter.onError(e -> {
            log.warn("[AI] SSE 连接异常: {}", e.getMessage());
            emitter.complete();
        });

        return emitter;
    }

    @Override
    public Long editMessage(Long messageId, String content) {
        AiMessage msg = aiMessageMapper.selectById(messageId);
        if (msg == null) {
            throw new BusinessException("消息不存在");
        }
        msg.setContent(content);
        aiMessageMapper.updateById(msg);
        aiMessageMapper.delete(new LambdaQueryWrapper<AiMessage>()
                .eq(AiMessage::getSessionId, msg.getSessionId())
                .gt(AiMessage::getSeqNo, msg.getSeqNo()));
        return msg.getSessionId();
    }

    @Override
    public void deleteMessage(Long messageId) {
        aiMessageMapper.deleteById(messageId);
    }

    @Override
    public SseEmitter regenerate(Long userId, Long sessionId) {
        AiSession session = aiSessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BusinessException(403, "会话不存在或无权访问");
        }

        List<AiMessage> recent = aiMessageMapper.selectList(new LambdaQueryWrapper<AiMessage>()
                .eq(AiMessage::getSessionId, sessionId)
                .orderByDesc(AiMessage::getSeqNo)
                .last("LIMIT " + CONTEXT_WINDOW));
        Collections.reverse(recent);
        if (recent.isEmpty()) {
            throw new BusinessException("会话内无消息，无法重新生成");
        }

        List<ChatMessage> chatMessages = buildChatMessages(recent);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        int aiSeqNo = nextSeqNo(sessionId);

        CompletableFuture.runAsync(() -> {
            try {
                executeWithTools(chatMessages, emitter, aiSeqNo, sessionId);
            } catch (Exception e) {
                log.error("[AI] 重新生成失败 sessionId={}", sessionId, e);
                sendSseEvent(emitter, "error", "message", e.getMessage() == null ? "生成失败" : e.getMessage());
                emitter.complete();
            }
        });

        emitter.onTimeout(emitter::complete);
        emitter.onError(e -> {
            log.warn("[AI] SSE 连接异常: {}", e.getMessage());
            emitter.complete();
        });

        return emitter;
    }

    // ==================== 核心工具调用循环 ====================

    /**
     * 执行带工具调用的对话生成
     * <p>
     * 算法：
     * 1. 用非流式模型 generate(messages, toolSpecs) 检查模型是否要调用工具
     * 2. 如果要调用工具：执行工具，把结果作为 ToolExecutionResultMessage 加入消息，推送"tool"事件，回到步骤1
     * 3. 如果不要调用工具（模型给出最终文本）：用流式模型重新生成最终回答，逐 token 推送
     * <p>
     * 这样工具调用阶段快速可靠，最终回答保持流式体验。
     */
    private void executeWithTools(List<ChatMessage> chatMessages, SseEmitter emitter,
                                   int aiSeqNo, Long sessionId) {
        List<ChatMessage> messages = new ArrayList<>(chatMessages);

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            // 用非流式模型检查是否需要调用工具
            Response<dev.langchain4j.data.message.AiMessage> response = chatLanguageModel.generate(messages, toolSpecifications);
            dev.langchain4j.data.message.AiMessage aiResponse = response.content();

            if (!aiResponse.hasToolExecutionRequests()) {
                // 模型给出最终文本答案（没有工具调用），改用流式重新生成以获得逐 token 体验
                streamFinalAnswer(messages, emitter, aiSeqNo, sessionId);
                return;
            }

            // 模型请求调用工具，执行所有工具调用
            messages.add(aiResponse);
            for (ToolExecutionRequest request : aiResponse.toolExecutionRequests()) {
                String toolName = request.name();
                // 推送工具调用通知（前端可显示"正在查询汇率..."）
                sendSseEvent(emitter, "tool", "name", toolName);
                log.info("[AI] 调用工具 {} args={}", toolName, request.arguments());
                String result = executeTool(request);
                messages.add(ToolExecutionResultMessage.from(request, result));
                log.info("[AI] 工具 {} 返回: {}", toolName,
                        result.length() > 200 ? result.substring(0, 200) + "..." : result);
            }
        }

        // 超过最大工具调用轮数，强制用流式生成最终回答
        log.warn("[AI] 工具调用达到最大轮数 {}，强制生成最终回答", MAX_TOOL_ROUNDS);
        streamFinalAnswer(messages, emitter, aiSeqNo, sessionId);
    }

    /**
     * 执行单个工具调用
     */
    private String executeTool(ToolExecutionRequest request) {
        try {
            ToolExecutor executor = toolExecutors.get(request.name());
            if (executor == null) {
                return "工具 " + request.name() + " 不存在";
            }
            return executor.execute(request, null);
        } catch (Exception e) {
            log.error("[AI] 工具 {} 执行失败", request.name(), e);
            return "工具执行失败：" + e.getMessage();
        }
    }

    /**
     * 用流式模型生成最终文本回答（不带工具规格，纯文本生成）
     */
    private void streamFinalAnswer(List<ChatMessage> messages, SseEmitter emitter,
                                    int aiSeqNo, Long sessionId) {
        StringBuilder accumulator = new StringBuilder();

        streamingChatLanguageModel.generate(messages, new StreamingResponseHandler<dev.langchain4j.data.message.AiMessage>() {
            @Override
            public void onNext(String token) {
                accumulator.append(token);
                sendSseEvent(emitter, "token", "content", token);
            }

            @Override
            public void onComplete(Response<dev.langchain4j.data.message.AiMessage> response) {
                String fullText = accumulator.length() > 0
                        ? accumulator.toString()
                        : response.content().text();
                // 保存 AI 消息
                AiMessage aiMsg = new AiMessage();
                aiMsg.setSessionId(sessionId);
                aiMsg.setRole("ASSISTANT");
                aiMsg.setContent(fullText);
                aiMsg.setSeqNo(aiSeqNo);
                aiMessageMapper.insert(aiMsg);
                // 更新会话时间
                AiSession update = new AiSession();
                update.setId(sessionId);
                aiSessionMapper.updateById(update);
                sendSseEvent(emitter, "done", "messageId", aiMsg.getId());
                emitter.complete();
            }

            @Override
            public void onError(Throwable error) {
                log.error("[AI] 流式生成失败 sessionId={}", sessionId, error);
                sendSseEvent(emitter, "error", "message",
                        error.getMessage() == null ? "生成失败" : error.getMessage());
                emitter.complete();
            }
        });
    }

    // ==================== 工具方法 ====================

    /**
     * 构建包含系统人设和历史对话的 ChatMessage 列表
     */
    private List<ChatMessage> buildChatMessages(List<AiMessage> recent) {
        List<ChatMessage> chatMessages = new ArrayList<>();
        chatMessages.add(SystemMessage.from(SYSTEM_PROMPT));
        for (AiMessage m : recent) {
            if ("USER".equals(m.getRole())) {
                chatMessages.add(UserMessage.from(m.getContent()));
            } else {
                chatMessages.add(dev.langchain4j.data.message.AiMessage.from(m.getContent()));
            }
        }
        return chatMessages;
    }

    /**
     * 计算会话内下一条消息的序号
     */
    private int nextSeqNo(Long sessionId) {
        AiMessage last = aiMessageMapper.selectOne(new LambdaQueryWrapper<AiMessage>()
                .eq(AiMessage::getSessionId, sessionId)
                .orderByDesc(AiMessage::getSeqNo)
                .last("LIMIT 1"));
        return last == null ? 1 : last.getSeqNo() + 1;
    }

    /**
     * 发送 SSE 事件（type + 单个字段），自动 JSON 序列化
     */
    private void sendSseEvent(SseEmitter emitter, String type, String valueKey, Object value) {
        try {
            Map<String, Object> map = new HashMap<>(2);
            map.put("type", type);
            map.put(valueKey, value);
            String json = objectMapper.writeValueAsString(map);
            emitter.send(SseEmitter.event().data(json));
        } catch (Exception e) {
            log.warn("[AI] SSE 推送 {} 失败: {}", type, e.getMessage());
        }
    }
}
