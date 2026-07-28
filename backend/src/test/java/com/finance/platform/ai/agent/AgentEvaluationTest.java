package com.finance.platform.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.platform.ai.entity.AiSession;
import com.finance.platform.ai.service.AiChatService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI Agent 工具调用集成评测
 * <p>
 * 使用真实 DeepSeek API，用 30 个标注用例测试模型的工具选择与参数提取能力。
 * <p>
 * 运行方式（二选一）：
 * <pre>
 * # 方式1：用系统属性启用（推荐，API key 从 application.yml 读取）
 * mvn test -Dtest=AgentEvaluationTest -DrunAgentEval=true
 *
 * # 方式2：用环境变量启用
 * set FINANCE_AI_API_KEY=sk-xxx
 * mvn test -Dtest=AgentEvaluationTest
 * </pre>
 * <p>
 * 输出：控制台打印详细评测报告，包括每个用例的期望/实际对比和准确率统计。
 */
@Slf4j
@DisplayName("AI Agent 工具调用集成评测")
@SpringBootTest
@EnabledIfSystemProperty(named = "runAgentEval", matches = "true", disabledReason = "需加 -DrunAgentEval=true 启用（避免日常构建消耗 API token）")
class AgentEvaluationTest {

    @Autowired private ChatLanguageModel chatLanguageModel;
    @Autowired private AiTools aiTools;
    @Autowired private AiChatService aiChatService;
    @Autowired private ObjectMapper objectMapper;

    /** 系统人设（与 AiChatServiceImpl 中一致） */
    private static final String SYSTEM_PROMPT = "你是跨境金融平台的 AI 合规顾问，"
            + "精通跨境支付、外汇结算、业财核算与合规风控。"
            + "当用户询问实时数据（如汇率、订单、利润、导入批次、对账状态）时，"
            + "请主动调用对应工具获取最新数据，基于真实数据回答。"
            + "请基于历史对话上下文，用专业、准确、简洁的中文回答用户问题。";

    /** 工具规格列表（在测试方法中初始化，避免字段初始化器时机早于 Spring 注入） */
    private List<ToolSpecification> toolSpecifications;

    // ==================== 评测用例定义 ====================

    /**
     * 评测用例
     */
    record EvalCase(
            String id,           // 用例ID
            String category,     // 分类
            String question,     // 用户问题
            String expectedTool, // 期望调用的工具名（null 表示不该调工具）
            Map<String, String> expectedParams, // 期望参数（关键词匹配）
            String description   // 用例说明
    ) {}

    /** 30 个标注用例 */
    private List<EvalCase> buildEvalCases() {
        List<EvalCase> cases = new ArrayList<>();

        // ===== 1. 工具选择正确性（13题，每个工具1题） =====
        cases.add(new EvalCase("T01", "工具选择", "现在美元兑人民币汇率多少？",
                "getLatestExchangeRate", Map.of("fromCurrency", "USD", "toCurrency", "CNY"), "汇率查询"));
        cases.add(new EvalCase("T02", "工具选择", "最近7天美元汇率走势如何？",
                "getExchangeRateHistory", Map.of("fromCurrency", "USD"), "汇率历史"));
        cases.add(new EvalCase("T03", "工具选择", "亚马逊平台最近30天订单情况",
                "queryOrders", Map.of("platform", "Amazon"), "订单查询"));
        cases.add(new EvalCase("T04", "工具选择", "2026年7月利润情况如何？",
                "queryProfitReport", Map.of("period", "202607"), "利润报表"));
        cases.add(new EvalCase("T05", "工具选择", "最近导入的批次清洗好了吗？",
                "queryImportBatches", Map.of(), "导入批次状态"));
        cases.add(new EvalCase("T06", "工具选择", "我们有哪些导入模板？",
                "queryImportTemplates", Map.of(), "导入模板列表"));
        cases.add(new EvalCase("T07", "工具选择", "1000美元等于多少人民币？",
                "getLatestExchangeRate", Map.of("fromCurrency", "USD", "toCurrency", "CNY"), "汇率换算（合并后）"));
        cases.add(new EvalCase("T08", "工具选择", "系统有哪些费用分摊规则？",
                "queryAllocationRules", Map.of(), "分摊规则"));
        cases.add(new EvalCase("T09", "工具选择", "银行流水对账状态怎么样？",
                "queryReconcileStatus", Map.of(), "对账状态"));
        cases.add(new EvalCase("T10", "工具选择", "订单 ORD-202607-0001 的详情",
                "queryOrderDetail", Map.of("orderNo", "ORD-202607-0001"), "订单详情"));
        cases.add(new EvalCase("T11", "工具选择", "最近7天有哪些操作日志？",
                "queryAuditLogs", Map.of(), "审计日志"));
        cases.add(new EvalCase("T12", "工具选择", "我们有哪些银行流水导入模板？",
                "queryImportTemplates", Map.of("sourceType", "BANK"), "银行流水模板"));
        cases.add(new EvalCase("T13", "工具选择", "分析一下2026年7月的利润归因",
                "analyzeProfit", Map.of("period", "202607"), "利润归因"));

        // ===== 2. 参数提取正确性（6题） =====
        cases.add(new EvalCase("P01", "参数提取", "欧元兑人民币汇率多少？",
                "getLatestExchangeRate", Map.of("fromCurrency", "EUR", "toCurrency", "CNY"), "EUR参数"));
        cases.add(new EvalCase("P02", "参数提取", "最近30天日元汇率走势",
                "getExchangeRateHistory", Map.of("fromCurrency", "JPY", "days", "30"), "JPY+30天"));
        cases.add(new EvalCase("P03", "参数提取", "Shopee平台最近7天的订单",
                "queryOrders", Map.of("platform", "Shopee"), "Shopee参数"));
        cases.add(new EvalCase("P04", "参数提取", "2026年6月的利润报表",
                "queryProfitReport", Map.of("period", "202606"), "6月period"));
        cases.add(new EvalCase("P05", "参数提取", "5000港币等于多少人民币？",
                "getLatestExchangeRate", Map.of("fromCurrency", "HKD", "toCurrency", "CNY"), "HKD换算（合并后）"));
        cases.add(new EvalCase("P06", "参数提取", "查一下平台账单的导入模板有哪些？",
                "queryImportTemplates", Map.of("sourceType", "PLATFORM"), "PLATFORM参数"));

        // ===== 3. 不该调工具（4题） =====
        cases.add(new EvalCase("N01", "不调工具", "你好，你是做什么的？",
                null, Map.of(), "自我介绍闲聊"));
        cases.add(new EvalCase("N02", "不调工具", "跨境收款需要遵守哪些税务合规要求？",
                null, Map.of(), "知识问答-税务"));
        cases.add(new EvalCase("N03", "不调工具", "什么是服务贸易外汇结算？",
                null, Map.of(), "知识问答-外汇"));
        cases.add(new EvalCase("N04", "不调工具", "谢谢，再见",
                null, Map.of(), "告别闲聊"));

        // ===== 4. 容错/边界（4题） =====
        cases.add(new EvalCase("B01", "边界", "订单 NOT-EXIST-999 的详情",
                "queryOrderDetail", Map.of("orderNo", "NOT-EXIST-999"), "不存在订单号"));
        cases.add(new EvalCase("B02", "边界", "查一下订单 NOT-EXIST 的详情",
                "queryOrderDetail", Map.of("orderNo", "NOT-EXIST"), "不存在订单号"));
        cases.add(new EvalCase("B03", "边界", "最近1天有哪些审计日志？",
                "queryAuditLogs", Map.of(), "1天审计日志"));
        cases.add(new EvalCase("B04", "边界", "2025年1月的利润情况",
                "queryProfitReport", Map.of("period", "202501"), "远期周期利润"));

        // ===== 5. 多工具协作（3题） =====
        cases.add(new EvalCase("M01", "多工具", "美元和欧元兑人民币汇率分别是多少？",
                "getLatestExchangeRate", Map.of(), "需调2次汇率查询"));
        cases.add(new EvalCase("M02", "多工具", "7月利润情况如何，银行对账状态怎么样？",
                "queryProfitReport", Map.of(), "利润+对账2个工具"));
        cases.add(new EvalCase("M03", "多工具", "现在美元汇率多少？1000美元等于多少人民币？",
                "getLatestExchangeRate", Map.of(), "汇率+换算2个工具"));

        return cases;
    }

    // ==================== 评测主逻辑 ====================

    @Test
    @DisplayName("运行30题评测并输出报告")
    void runEvaluation() throws Exception {
        // 初始化工具规格（此时 aiTools 已被 Spring 注入）
        this.toolSpecifications = ToolSpecifications.toolSpecificationsFrom(aiTools);
        log.info("工具规格数量: {}", toolSpecifications.size());

        List<EvalCase> cases = buildEvalCases();
        List<EvalResult> results = new ArrayList<>();
        log.info("=== AI Agent 工具调用评测开始，共 {} 个用例 ===", cases.size());

        for (EvalCase evalCase : cases) {
            EvalResult result = evaluateOne(evalCase);
            results.add(result);
            log.info("[{}] {} | 期望:{} 实际:{} | 参数匹配:{} | {}",
                    evalCase.id(), evalCase.category(),
                    evalCase.expectedTool(), result.actualTool(),
                    result.paramMatched() ? "✓" : "✗",
                    result.passed() ? "PASS" : "FAIL");
        }

        // 统计指标
        printReport(results);
        // 断言整体准确率（至少 80% 通过）
        double accuracy = (double) results.stream().filter(EvalResult::passed).count() / results.size();
        log.info("=== 总准确率: {}% ===", String.format("%.1f", accuracy * 100));
        assertThat(accuracy).as("工具调用准确率应 >= 80%").isGreaterThanOrEqualTo(0.8);
    }

    /**
     * 评测单个用例
     */
    private EvalResult evaluateOne(EvalCase evalCase) {
        try {
            // 构建消息
            List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from(SYSTEM_PROMPT));
            messages.add(UserMessage.from(evalCase.question()));

            // 调用模型（带工具规格）
            Response<AiMessage> response = chatLanguageModel.generate(messages, toolSpecifications);
            AiMessage aiMessage = response.content();

            String actualTool = null;
            Map<String, String> actualParams = new HashMap<>();
            boolean paramMatched = false;

            if (aiMessage.hasToolExecutionRequests()) {
                List<ToolExecutionRequest> requests = aiMessage.toolExecutionRequests();
                // 取第一个工具调用（多工具场景只校验第一个是否正确）
                ToolExecutionRequest first = requests.get(0);
                actualTool = first.name();
                actualParams = parseParams(first.arguments());

                // 参数匹配检查：期望参数的每个 key-value 都在实际参数中包含
                paramMatched = checkParams(evalCase.expectedParams(), actualParams);
            }

            boolean passed;
            if (evalCase.expectedTool() == null) {
                // 不该调工具：实际也没调才算通过
                passed = actualTool == null;
            } else {
                // 该调工具：工具名匹配 + 参数匹配
                passed = evalCase.expectedTool().equals(actualTool) && paramMatched;
            }

            return new EvalResult(evalCase, actualTool, actualParams, paramMatched, passed, null);
        } catch (Exception e) {
            log.error("[{}] 评测异常: {}", evalCase.id(), e.getMessage());
            return new EvalResult(evalCase, null, Map.of(), false, false, e.getMessage());
        }
    }

    /**
     * 解析工具参数 JSON 字符串
     */
    private Map<String, String> parseParams(String arguments) {
        Map<String, String> params = new HashMap<>();
        if (arguments == null || arguments.isBlank()) return params;
        try {
            JsonNode node = objectMapper.readTree(arguments);
            node.fields().forEachRemaining(entry ->
                    params.put(entry.getKey(), entry.getValue().asText()));
        } catch (Exception e) {
            log.warn("解析参数失败: {}", arguments);
        }
        return params;
    }

    /**
     * 参数匹配检查：期望参数的所有 key-value 都在实际参数中包含（值包含关键词即可）
     */
    private boolean checkParams(Map<String, String> expected, Map<String, String> actual) {
        if (expected.isEmpty()) return true; // 无期望参数则跳过
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            String actualVal = actual.get(entry.getKey());
            if (actualVal == null || !actualVal.contains(entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 打印评测报告
     */
    private void printReport(List<EvalResult> results) {
        int total = results.size();
        long passed = results.stream().filter(EvalResult::passed).count();
        long failed = total - passed;

        // 按分类统计
        Map<String, Long> byCategory = results.stream()
                .collect(Collectors.groupingBy(r -> r.evalCase().category(), Collectors.counting()));
        Map<String, Long> passedByCategory = results.stream()
                .filter(EvalResult::passed)
                .collect(Collectors.groupingBy(r -> r.evalCase().category(), Collectors.counting()));

        log.info("");
        log.info("╔══════════════════════════════════════════════════════╗");
        log.info("║          AI Agent 工具调用评测报告                    ║");
        log.info("╠══════════════════════════════════════════════════════╣");
        log.info("║  总用例数: {}                                          ║", total);
        log.info("║  通过: {}  |  失败: {}                               ║", passed, failed);
        log.info("║  总准确率: {}%                                       ║", String.format("%.1f", (double) passed / total * 100));
        log.info("╠══════════════════════════════════════════════════════╣");
        log.info("║  分类统计:                                            ║");
        byCategory.forEach((cat, count) -> {
            long catPassed = passedByCategory.getOrDefault(cat, 0L);
            log.info("║    {}: {}/{} ({}%)                            ║",
                    cat, catPassed, count, String.format("%.0f", (double) catPassed / count * 100));
        });
        log.info("╠══════════════════════════════════════════════════════╣");
        log.info("║  失败用例详情:                                        ║");
        results.stream().filter(r -> !r.passed()).forEach(r -> {
            log.info("║    [{}] {} 期望:{} 实际:{} 原因:{}                  ║",
                    r.evalCase().id(), r.evalCase().description(),
                    r.evalCase().expectedTool(), r.actualTool(),
                    r.error() != null ? r.error() : "工具或参数不匹配");
        });
        log.info("╚══════════════════════════════════════════════════════╝");
    }

    /** 评测结果 */
    record EvalResult(
            EvalCase evalCase,
            String actualTool,
            Map<String, String> actualParams,
            boolean paramMatched,
            boolean passed,
            String error
    ) {}
}
