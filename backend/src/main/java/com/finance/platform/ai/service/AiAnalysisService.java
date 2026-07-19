package com.finance.platform.ai.service;

/**
 * AI 分析服务接口
 * <p>
 * 结合利润报表数据与大模型，提供利润归因分析与完整报告生成能力。
 */
public interface AiAnalysisService {

    /**
     * 利润归因分析：基于周期利润指标生成分析文本
     *
     * @param period 核算周期，如 202607
     * @return 分析报告文本
     */
    String analyzeProfit(String period);

    /**
     * 生成完整报告：结合 RAG 知识上下文与大模型生成完整分析报告
     *
     * @param period 核算周期
     * @return 完整报告文本
     */
    String generateReport(String period);
}
