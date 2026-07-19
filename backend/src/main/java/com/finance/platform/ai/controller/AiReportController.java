package com.finance.platform.ai.controller;

import com.finance.platform.ai.service.AiAnalysisService;
import com.finance.platform.common.core.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 智能归因报告生成接口
 * <p>
 * 触发大模型对指定周期的利润进行归因分析，返回 AI 报告文本。
 */
@Slf4j
@RestController
@RequestMapping("/ai/report")
@RequiredArgsConstructor
public class AiReportController {

    private final AiAnalysisService aiAnalysisService;

    /**
     * 触发利润归因分析
     *
     * @param period 核算周期，如 202607
     * @return AI 报告文本
     */
    @PostMapping("/profit/{period}")
    public Result<String> profit(@PathVariable String period) {
        log.info("[AI] 触发利润归因分析 period={}", period);
        return Result.success(aiAnalysisService.analyzeProfit(period));
    }
}
