package com.finance.platform.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * LangChain4j 大模型对接配置
 * <p>
 * 当前默认对接 DeepSeek（兼容 OpenAI 协议）。
 * 切换厂商时只需替换 base-url / model-name，或新增对应 Model 的 Bean。
 */
@Configuration
public class LangChainConfig {

    @Value("${finance.langchain.api-key}")
    private String apiKey;

    @Value("${finance.langchain.base-url}")
    private String baseUrl;

    @Value("${finance.langchain.model-name}")
    private String modelName;

    @Value("${finance.langchain.temperature:0.3}")
    private double temperature;

    @Value("${finance.langchain.timeout:60000}")
    private long timeout;

    @Bean
    public ChatLanguageModel chatLanguageModel() {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(temperature)
                .timeout(Duration.ofMillis(timeout))
                .build();
    }

    /**
     * 流式对话模型 Bean
     * <p>
     * 供 AI 顾问多轮对话使用，逐 token 推送 SSE 流式响应。
     * 对接 DeepSeek（兼容 OpenAI 协议）。
     */
    @Bean
    public StreamingChatLanguageModel streamingChatLanguageModel() {
        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(temperature)
                .timeout(Duration.ofMillis(timeout))
                .build();
    }
}
