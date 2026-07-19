package com.finance.platform.ai.service.impl;

import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.platform.accounting.entity.ProfitReport;
import com.finance.platform.accounting.mapper.ProfitReportMapper;
import com.finance.platform.ai.service.AiAnalysisService;
import com.finance.platform.ai.service.RagKnowledgeService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 利润分析实现
 * <p>
 * 启动时通过 ClassPathResource 加载 prompt/ProfitAnalysisPrompt.txt 模板并缓存；
 * 分析时汇总周期利润指标填充占位符，调用 ChatLanguageModel 生成结果。
 * generateReport 额外召回 RAG 知识片段拼入上下文，产出更完整的报告。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiAnalysisServiceImpl implements AiAnalysisService {

    private final ChatLanguageModel chatLanguageModel;
    private final ProfitReportMapper profitReportMapper;
    private final RagKnowledgeService ragKnowledgeService;

    /** Prompt 模板缓存 */
    private String promptTemplate;

    @PostConstruct
    public void loadTemplate() {
        try {
            ClassPathResource resource = new ClassPathResource("prompt/ProfitAnalysisPrompt.txt");
            promptTemplate = IoUtil.readUtf8(resource.getInputStream());
            log.info("[AI] 利润分析 Prompt 模板加载完成，长度={}", promptTemplate.length());
        } catch (Exception e) {
            log.error("[AI] 加载利润分析 Prompt 模板失败，使用降级模板", e);
            promptTemplate = "请分析 {period} 周期的利润情况：总收入 {totalRevenue}，总成本 {totalCost}，总利润 {totalProfit}，利润率 {profitRate}。";
        }
    }

    @Override
    public String analyzeProfit(String period) {
        Map<String, String> metrics = computeMetrics(period);
        String prompt = renderTemplate(promptTemplate, metrics);
        log.info("[AI] 触发利润归因分析 period={}", period);
        return chatLanguageModel.generate(prompt);
    }

    @Override
    public String generateReport(String period) {
        Map<String, String> metrics = computeMetrics(period);
        String base = renderTemplate(promptTemplate, metrics);
        // 召回相关知识片段，拼接到 prompt 上下文
        List<String> knowledge = ragKnowledgeService.search("利润分析 " + period, 3);
        StringBuilder prompt = new StringBuilder();
        if (knowledge != null && !knowledge.isEmpty()) {
            prompt.append("参考知识：\n");
            for (String k : knowledge) {
                prompt.append("- ").append(k).append("\n");
            }
            prompt.append("\n");
        }
        prompt.append(base);
        log.info("[AI] 生成完整利润报告 period={} 知识片段数={}", period, knowledge == null ? 0 : knowledge.size());
        return chatLanguageModel.generate(prompt.toString());
    }

    /** 汇总周期利润指标：总收入、总成本、总利润、利润率 */
    private Map<String, String> computeMetrics(String period) {
        List<ProfitReport> reports = profitReportMapper.selectList(new LambdaQueryWrapper<ProfitReport>()
                .eq(StrUtil.isNotBlank(period), ProfitReport::getPeriod, period));
        BigDecimal totalRevenue = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalProfit = BigDecimal.ZERO;
        for (ProfitReport r : reports) {
            totalRevenue = totalRevenue.add(r.getCnyAmount() == null ? BigDecimal.ZERO : r.getCnyAmount());
            totalCost = totalCost.add(r.getCostAmount() == null ? BigDecimal.ZERO : r.getCostAmount());
            totalProfit = totalProfit.add(r.getProfitAmount() == null ? BigDecimal.ZERO : r.getProfitAmount());
        }
        BigDecimal profitRate = totalRevenue.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : totalProfit.divide(totalRevenue, 4, RoundingMode.HALF_UP);
        Map<String, String> metrics = new HashMap<>();
        metrics.put("period", period == null ? "" : period);
        metrics.put("totalRevenue", totalRevenue.toPlainString());
        metrics.put("totalCost", totalCost.toPlainString());
        metrics.put("totalProfit", totalProfit.toPlainString());
        metrics.put("profitRate", profitRate.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP) + "%");
        return metrics;
    }

    /** 简单占位符替换：将 {key} 替换为对应值 */
    private String renderTemplate(String template, Map<String, String> metrics) {
        String result = template;
        for (Map.Entry<String, String> e : metrics.entrySet()) {
            result = result.replace("{" + e.getKey() + "}", e.getValue());
        }
        return result;
    }
}
