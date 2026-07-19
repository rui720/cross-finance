package com.finance.platform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步线程池配置
 * <p>
 * 分别为 ETL 解析、AI 分析提供独立线程池，避免相互影响。
 */
@Configuration
public class ThreadPoolConfig {

    @Value("${finance.thread-pool.etl-core:2}")
    private int etlCore;

    @Value("${finance.thread-pool.etl-max:8}")
    private int etlMax;

    @Value("${finance.thread-pool.etl-queue:100}")
    private int etlQueue;

    @Value("${finance.thread-pool.ai-core:4}")
    private int aiCore;

    @Value("${finance.thread-pool.ai-max:16}")
    private int aiMax;

    @Value("${finance.thread-pool.ai-queue:200}")
    private int aiQueue;

    /**
     * ETL 异步线程池：用于账单/银行流水解析等 IO 密集型任务
     */
    @Bean("etlExecutor")
    public Executor etlExecutor() {
        return build("etl-", etlCore, etlMax, etlQueue);
    }

    /**
     * AI 异步线程池：用于 LangChain4j 调用、RAG 检索等大模型任务
     */
    @Bean("aiExecutor")
    public Executor aiExecutor() {
        return build("ai-", aiCore, aiMax, aiQueue);
    }

    private ThreadPoolTaskExecutor build(String prefix, int core, int max, int queue) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queue);
        executor.setThreadNamePrefix(prefix);
        // 拒绝策略：由调用线程执行，避免任务丢失
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
