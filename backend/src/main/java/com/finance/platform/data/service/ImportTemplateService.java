package com.finance.platform.data.service;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.platform.common.exception.BusinessException;
import com.finance.platform.data.entity.ImportTemplate;
import com.finance.platform.data.mapper.ImportTemplateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 导入模板服务
 * <p>
 * 提供模板 CRUD、按来源查询默认模板、上传自定义模板等能力。
 * <p>
 * 默认模板机制：
 * - is_default=1 的模板为系统默认，不可删除
 * - 用户上传的模板 is_default=0，可随时删除
 * - 删除自定义模板后，导入时自动回退到默认模板
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportTemplateService {

    private final ImportTemplateMapper importTemplateMapper;
    private final ObjectMapper objectMapper;

    /** 中文列名 → 实体字段名映射（PLATFORM/BANK 通用） */
    private static final Map<String, String> ORDER_FIELD_MAP = Map.ofEntries(
            Map.entry("订单号", "orderNo"),
            Map.entry("交易流水号", "orderNo"),
            Map.entry("平台", "platform"),
            Map.entry("银行", "platform"),
            Map.entry("店铺ID", "shopId"),
            Map.entry("币种", "currency"),
            Map.entry("金额", "amount"),
            Map.entry("交易金额", "amount"),
            Map.entry("手续费", "fee"),
            Map.entry("平台费", "fee"),
            Map.entry("结算金额", "settleAmount"),
            Map.entry("下单时间", "orderTime"),
            Map.entry("交易时间", "orderTime"),
            Map.entry("结算时间", "settleTime"),
            Map.entry("入账时间", "settleTime")
    );

    /** 中文列名 → 实体字段名映射（COST） */
    private static final Map<String, String> COST_FIELD_MAP = Map.ofEntries(
            Map.entry("费用类型", "costType"),
            Map.entry("金额", "amount"),
            Map.entry("币种", "currency"),
            Map.entry("核算周期", "period"),
            Map.entry("订单号", "orderNo"),
            Map.entry("收款方", "payee"),
            Map.entry("费用日期", "costDate"),
            Map.entry("备注", "remark")
    );

    /** 各 sourceType 的默认清洗规则 */
    private static final Map<String, String> DEFAULT_CLEAN_RULES = Map.of(
            "PLATFORM", "trimRule,defaultCurrencyRule,filterInvalidRule,currencyConvertRule,deduplicateRule",
            "BANK", "trimRule,defaultCurrencyRule,filterInvalidRule,currencyConvertRule,deduplicateRule",
            "COST", "trimRule,defaultCurrencyRule,filterInvalidRule"
    );

    /**
     * 根据 ID 查询模板
     */
    public ImportTemplate getById(Long id) {
        return importTemplateMapper.selectById(id);
    }

    /**
     * 按数据来源查询启用模板列表（默认模板排在最前）
     */
    public List<ImportTemplate> listBySource(String sourceType) {
        return importTemplateMapper.selectList(new LambdaQueryWrapper<ImportTemplate>()
                .eq(ImportTemplate::getSourceType, sourceType)
                .eq(ImportTemplate::getStatus, 1)
                .orderByDesc(ImportTemplate::getIsDefault)
                .orderByAsc(ImportTemplate::getId));
    }

    /**
     * 获取默认模板（is_default=1 的第一条；若无则取第一条启用的）
     */
    public ImportTemplate getDefaultTemplate(String sourceType) {
        List<ImportTemplate> list = listBySource(sourceType);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 新增模板
     */
    public Long save(ImportTemplate template) {
        if (template.getIsDefault() == null) {
            template.setIsDefault(0);
        }
        importTemplateMapper.insert(template);
        return template.getId();
    }

    /**
     * 更新模板
     */
    public void update(ImportTemplate template) {
        importTemplateMapper.updateById(template);
    }

    /**
     * 删除模板（逻辑删除）
     * <p>
     * 默认模板（is_default=1）不可删除，抛出 BusinessException。
     */
    public void delete(Long id) {
        ImportTemplate template = importTemplateMapper.selectById(id);
        if (template == null) {
            throw new BusinessException("模板不存在");
        }
        if (template.getIsDefault() != null && template.getIsDefault() == 1) {
            throw new BusinessException("默认模板不可删除，删除自定义模板后默认模板将自动生效");
        }
        importTemplateMapper.deleteById(id);
    }

    /**
     * 上传自定义模板：解析文件表头，按中文字段名智能匹配生成 column_mapping，保存为自定义模板
     * <p>
     * 支持 Excel(.xlsx/.xls) 和 CSV 文件。表头行需使用中文列名（如"订单号""金额"等）。
     * 匹配成功的字段写入 column_mapping，未匹配的字段忽略。
     *
     * @param file         Excel/CSV 文件（只需表头行，也可含数据行）
     * @param sourceType   数据来源：PLATFORM/BANK/COST
     * @param templateName 模板名称（可选，空则用文件名）
     * @return 新建的模板 ID
     */
    public Long uploadTemplate(MultipartFile file, String sourceType, String templateName) {
        try {
            // 1. 读取表头
            List<String> headers = readHeaders(file);

            // 2. 获取字段映射表
            Map<String, String> fieldMap = "COST".equals(sourceType) ? COST_FIELD_MAP : ORDER_FIELD_MAP;

            // 3. 匹配表头生成 column_mapping
            Map<String, String> columnMapping = new LinkedHashMap<>();
            for (String header : headers) {
                String trimmed = header.trim();
                String fieldName = fieldMap.get(trimmed);
                if (fieldName != null && !columnMapping.containsKey(fieldName)) {
                    columnMapping.put(fieldName, trimmed);
                }
            }

            if (columnMapping.isEmpty()) {
                throw new BusinessException(
                        "未识别到任何有效字段，请确保表头使用中文列名（如：订单号、金额、币种、手续费等）");
            }

            // 4. 创建模板
            ImportTemplate template = new ImportTemplate();
            String fileName = file.getOriginalFilename();
            String name = (templateName != null && !templateName.isBlank())
                    ? templateName
                    : (fileName != null ? fileName.replaceFirst("\\.[^.]+$", "") : "自定义模板");
            template.setTemplateName(name);
            template.setSourceType(sourceType);
            template.setFileType(fileName != null && fileName.toLowerCase().endsWith(".csv") ? "CSV" : "EXCEL");
            template.setColumnMapping(objectMapper.writeValueAsString(columnMapping));
            template.setCleanRules(DEFAULT_CLEAN_RULES.getOrDefault(sourceType,
                    "trimRule,defaultCurrencyRule,filterInvalidRule"));
            template.setHeaderRow(1);
            template.setSheetNo(0);
            template.setAiGenerated(0);
            template.setRemark("用户上传的自定义模板");
            template.setStatus(1);
            template.setIsDefault(0);

            importTemplateMapper.insert(template);
            log.info("[模板上传] 成功创建自定义模板 sourceType={}, name={}, fields={}",
                    sourceType, name, columnMapping.keySet());
            return template.getId();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("[模板上传] 解析失败 sourceType={}", sourceType, e);
            throw new BusinessException("解析模板文件失败：" + e.getMessage());
        }
    }

    /**
     * 读取文件表头（第一行）
     * <p>
     * CSV：用 BufferedReader 读取第一行，按逗号分割
     * Excel：用 EasyExcel 读取首行
     */
    private List<String> readHeaders(MultipartFile file) throws IOException {
        String fileName = file.getOriginalFilename();
        if (fileName != null && fileName.toLowerCase().endsWith(".csv")) {
            // CSV 文件：读取第一行
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                if (line == null) {
                    return List.of();
                }
                // 处理 BOM
                if (line.startsWith("\ufeff")) {
                    line = line.substring(1);
                }
                return Arrays.stream(line.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toList());
            }
        }

        // Excel 文件：EasyExcel 读取首行（headRowNumber=0 不跳过表头）
        List<Map<Integer, String>> allRows = EasyExcel.read(file.getInputStream())
                .sheet(0)
                .headRowNumber(0)
                .doReadSync();
        if (allRows.isEmpty()) {
            return List.of();
        }
        Map<Integer, String> headerRow = allRows.get(0);
        return headerRow.values().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}
