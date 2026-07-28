package com.finance.platform.data.etl.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.platform.data.entity.ImportTemplate;
import com.finance.platform.data.etl.parser.FileParser;
import com.finance.platform.data.etl.parser.FileParserFactory;
import com.finance.platform.data.service.ImportTemplateService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 智能字段识别服务（方案 D 核心）
 * <p>
 * 调用 LLM 分析上传文件的表头与样例数据，自动推荐字段映射配置。
 * <p>
 * 工作流程：
 * 1. 读取文件前 10 行（表头 + 样例）
 * 2. 构建 Prompt 描述目标 schema（RawOrder 9 个字段）
 * 3. 调用 ChatLanguageModel 让 LLM 输出 JSON 映射
 * 4. 解析 LLM 输出，创建 ImportTemplate（aiGenerated=1）
 * 5. 返回模板供前端展示，用户可确认/调整后保存
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportTemplateAiService {

    private final FileParserFactory fileParserFactory;
    private final ChatLanguageModel chatLanguageModel;
    private final ImportTemplateService importTemplateService;
    private final ObjectMapper objectMapper;

    /** RawOrder 可映射的目标字段及其语义说明 */
    private static final String TARGET_SCHEMA = """
            {
              "orderNo": "订单号/订单编号",
              "platform": "平台名称（如 Amazon/Shopee/eBay/Rakuten）",
              "shopId": "店铺 ID/店铺编号",
              "currency": "币种代码（如 USD/EUR/HKD/JPY/CNY）",
              "amount": "订单金额/外币金额",
              "fee": "平台费/手续费/佣金",
              "settleAmount": "结算金额",
              "orderTime": "下单时间/订单创建时间",
              "settleTime": "结算时间/到账时间"
            }
            """;

    /** 推荐清洗规则（平台账单） */
    private static final String DEFAULT_CLEAN_RULES_PLATFORM =
            "trimRule,defaultCurrencyRule,filterInvalidRule,currencyConvertRule,deduplicateRule";

    /** 推荐清洗规则（银行流水，无币种换算） */
    private static final String DEFAULT_CLEAN_RULES_BANK =
            "trimRule,defaultCurrencyRule,filterInvalidRule,deduplicateRule";

    /**
     * AI 识别文件表头并推荐字段映射
     *
     * @param file       上传的文件
     * @param sourceType 数据来源（PLATFORM/BANK）
     * @return 识别结果（含推荐模板，未入库）
     */
    public AiRecognizeResult recognize(MultipartFile file, String sourceType) {
        // 1. 读取表头与样例
        FileParser parser = fileParserFactory.getParserByFileName(file.getOriginalFilename());
        Map<String, Object> headData = parser.readHeadAndSamples(file, 10);
        @SuppressWarnings("unchecked")
        List<String> headers = (List<String>) headData.get("headers");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> samples = (List<Map<String, String>>) headData.get("samples");

        if (headers == null || headers.isEmpty()) {
            throw new IllegalArgumentException("文件表头为空，无法识别");
        }

        // 2. 构建 Prompt
        String prompt = buildPrompt(headers, samples, sourceType);

        // 3. 调用 LLM
        log.info("[AI识别] 文件={} 表头数={} 样例数={} 调用 LLM 识别字段映射",
                file.getOriginalFilename(), headers.size(), samples.size());
        String llmResponse = chatLanguageModel.generate(prompt);

        // 4. 解析 LLM 输出
        Map<String, String> mapping = parseMappingFromLlm(llmResponse, headers);

        // 5. 构建识别结果
        String fileType = parser.supportedType();
        String cleanRules = "BANK".equals(sourceType) ? DEFAULT_CLEAN_RULES_BANK : DEFAULT_CLEAN_RULES_PLATFORM;

        ImportTemplate template = new ImportTemplate();
        template.setTemplateName("AI识别-" + file.getOriginalFilename());
        template.setSourceType(sourceType);
        template.setFileType(fileType);
        template.setColumnMapping(toJson(mapping));
        template.setCleanRules(cleanRules);
        template.setHeaderRow(1);
        template.setSheetNo(0);
        template.setAiGenerated(1);
        template.setRemark("AI 自动识别生成，LLM 原始响应：" + truncate(llmResponse, 200));
        template.setStatus(1);

        return new AiRecognizeResult(headers, samples, mapping, template, llmResponse);
    }

    /**
     * 识别并直接保存为模板
     */
    public Long recognizeAndSave(MultipartFile file, String sourceType) {
        AiRecognizeResult result = recognize(file, sourceType);
        return importTemplateService.save(result.template());
    }

    private String buildPrompt(List<String> headers, List<Map<String, String>> samples, String sourceType) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个数据集成专家。请根据上传文件的表头和样例数据，识别每个表头对应到目标 schema 的哪个字段。\n\n");
        sb.append("【目标 schema 字段及语义】\n").append(TARGET_SCHEMA).append("\n\n");
        sb.append("【数据来源】").append(sourceType).append("\n\n");
        sb.append("【文件表头（按列顺序）】\n");
        for (int i = 0; i < headers.size(); i++) {
            sb.append(i + 1).append(". ").append(headers.get(i)).append("\n");
        }
        sb.append("\n【样例数据（前").append(samples.size()).append("行）】\n");
        for (int i = 0; i < samples.size(); i++) {
            sb.append("行").append(i + 1).append(": ").append(samples.get(i)).append("\n");
        }
        sb.append("\n【任务要求】\n");
        sb.append("1. 将每个表头映射到目标 schema 中最匹配的字段（驼峰命名）\n");
        sb.append("2. 无法匹配的字段不要包含在结果中\n");
        sb.append("3. 只输出 JSON，不要任何解释或 markdown 代码块标记\n");
        sb.append("4. JSON 格式：{\"目标字段名\":\"表头原名\",...}\n");
        sb.append("\n【示例输出】\n");
        sb.append("{\"orderNo\":\"订单号\",\"amount\":\"金额\",\"currency\":\"币种\"}\n");
        return sb.toString();
    }

    /**
     * 从 LLM 响应中解析字段映射 JSON
     * <p>
     * 容错处理：LLM 可能返回带 markdown 代码块、多余解释等情况。
     */
    private Map<String, String> parseMappingFromLlm(String llmResponse, List<String> headers) {
        if (llmResponse == null || llmResponse.isBlank()) {
            log.warn("[AI识别] LLM 返回空响应，使用空映射");
            return new LinkedHashMap<>();
        }
        // 提取 JSON 部分（容错：去掉 markdown 代码块标记）
        String json = extractJson(llmResponse);
        try {
            Map<String, String> mapping = objectMapper.readValue(json, new TypeReference<>() {});
            // 过滤掉表头中不存在的映射
            Map<String, String> filtered = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : mapping.entrySet()) {
                if (e.getValue() != null && headers.contains(e.getValue().trim())) {
                    filtered.put(e.getKey(), e.getValue().trim());
                }
            }
            log.info("[AI识别] LLM 映射解析成功，共 {} 个字段：{}", filtered.size(), filtered.keySet());
            return filtered;
        } catch (Exception e) {
            log.warn("[AI识别] LLM 响应 JSON 解析失败，原始响应：{}，错误：{}", truncate(llmResponse, 200), e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private String extractJson(String text) {
        // 去掉 markdown 代码块 ```json ... ```
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
            trimmed = trimmed.trim();
        }
        // 提取第一个 { 到最后一个 }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    private String toJson(Map<String, String> mapping) {
        try {
            return objectMapper.writeValueAsString(mapping);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /**
     * AI 识别结果
     */
    public record AiRecognizeResult(
            List<String> headers,
            List<Map<String, String>> samples,
            Map<String, String> recommendedMapping,
            ImportTemplate template,
            String llmRawResponse
    ) {}
}
