package com.finance.platform.data.controller;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.write.metadata.style.WriteCellStyle;
import com.alibaba.excel.write.metadata.style.WriteFont;
import com.alibaba.excel.write.style.HorizontalCellStyleStrategy;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.platform.common.constant.BusinessConstants;
import com.finance.platform.common.core.Result;
import com.finance.platform.common.utils.FileValidationUtils;
import com.finance.platform.data.entity.ExchangeRateSnapshot;
import com.finance.platform.data.entity.ExtraCost;
import com.finance.platform.data.entity.ImportBatch;
import com.finance.platform.data.entity.ImportTemplate;
import com.finance.platform.data.etl.ai.ImportTemplateAiService;
import com.finance.platform.data.mapper.RawOrderMapper;
import com.finance.platform.data.service.DataEtlService;
import com.finance.platform.data.service.ExchangeRateImportService;
import com.finance.platform.data.service.ExchangeRateService;
import com.finance.platform.data.service.ExtraCostService;
import com.finance.platform.data.service.ImportBatchService;
import com.finance.platform.data.service.ImportTemplateService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 账单/银行流水文件导入接口（重构版）
 * <p>
 * 新增能力：
 * - 支持 templateId 指定导入模板
 * - 新增 AI 字段识别接口 /data/import/ai-recognize
 * - 新增模板管理接口 /data/import/template/*
 * - 批次列表改为查询 import_batch 表（状态机）
 * <p>
 * 权限：查询批次记录对 ADMIN/FINANCE/OPERATOR 开放；
 * 上传文件、清洗数据等写操作仅 ADMIN/FINANCE 可用（运营只读）。
 */
@Slf4j
@RestController
@RequestMapping("/data/import")
@RequiredArgsConstructor
public class DataImportController {

    private final DataEtlService dataEtlService;
    private final RawOrderMapper rawOrderMapper;
    private final ImportBatchService importBatchService;
    private final ImportTemplateService importTemplateService;
    private final ImportTemplateAiService importTemplateAiService;
    private final ExchangeRateImportService exchangeRateImportService;
    private final ExchangeRateService exchangeRateService;
    private final ExtraCostService extraCostService;
    private final ObjectMapper objectMapper;

    /**
     * 分页查询导入批次记录（基于 import_batch 状态机表，数据库分页）
     */
    @GetMapping("/bill/page")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','OPERATOR')")
    public Result<IPage<ImportBatch>> billPage(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String sourceType) {
        return Result.success(importBatchService.page(page, size, sourceType));
    }

    /**
     * 上传平台账单文件（可指定模板，不指定则用默认模板）
     *
     * @param file       Excel/CSV 文件
     * @param source     数据来源
     * @param templateId 导入模板 ID（可选）
     * @return 导入结果（含重复检测信息）
     */
    @PostMapping("/bill")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public Result<DataEtlService.BillImportResult> importBill(@RequestParam("file") MultipartFile file,
                                     @RequestParam(defaultValue = BusinessConstants.SOURCE_PLATFORM) String source,
                                     @RequestParam(required = false) Long templateId) {
        FileValidationUtils.validateExcelOrCsv(file);
        DataEtlService.BillImportResult result = dataEtlService.importBill(file, source, templateId);
        return Result.success(result);
    }

    /**
     * 上传银行流水文件
     */
    @PostMapping("/bank")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public Result<DataEtlService.BillImportResult> importBankFlow(@RequestParam("file") MultipartFile file,
                                         @RequestParam(required = false) Long templateId) {
        FileValidationUtils.validateExcelOrCsv(file);
        DataEtlService.BillImportResult result = dataEtlService.importBill(file, BusinessConstants.SOURCE_BANK, templateId);
        return Result.success(result);
    }

    /**
     * 按批次号清洗数据：基于模板配置的规则链执行
     */
    @PostMapping("/clean")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public Result<Void> clean(@RequestParam String batchNo) {
        log.info("[ETL] 触发数据清洗 batchNo={}", batchNo);
        dataEtlService.cleanData(batchNo);
        return Result.success();
    }

    /**
     * AI 智能识别文件字段映射（方案 D）
     * <p>
     * 上传文件 → 读取表头与样例 → 调用 LLM 推荐 column_mapping → 返回建议模板（未入库）
     */
    @PostMapping("/ai-recognize")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public Result<ImportTemplateAiService.AiRecognizeResult> aiRecognize(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = BusinessConstants.SOURCE_PLATFORM) String sourceType) {
        FileValidationUtils.validateExcelOrCsv(file);
        log.info("[ETL] AI 识别字段映射 file={}, source={}", file.getOriginalFilename(), sourceType);
        ImportTemplateAiService.AiRecognizeResult result = importTemplateAiService.recognize(file, sourceType);
        return Result.success(result);
    }

    /**
     * AI 识别并直接保存为模板
     */
    @PostMapping("/ai-recognize-save")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public Result<Long> aiRecognizeAndSave(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = BusinessConstants.SOURCE_PLATFORM) String sourceType) {
        FileValidationUtils.validateExcelOrCsv(file);
        Long templateId = importTemplateAiService.recognizeAndSave(file, sourceType);
        return Result.success(templateId);
    }

    // ==================== 模板管理接口 ====================

    /**
     * 查询模板列表
     * <p>
     * sourceType 为空时返回全部来源（PLATFORM+BANK+COST）的模板。
     */
    @GetMapping("/template/list")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','OPERATOR')")
    public Result<List<ImportTemplate>> templateList(@RequestParam(required = false) String sourceType) {
        if (sourceType == null || sourceType.isBlank()) {
            List<ImportTemplate> list = new ArrayList<>();
            for (String s : new String[]{BusinessConstants.SOURCE_PLATFORM, BusinessConstants.SOURCE_BANK, "COST"}) {
                list.addAll(importTemplateService.listBySource(s));
            }
            return Result.success(list);
        }
        return Result.success(importTemplateService.listBySource(sourceType));
    }

    /**
     * 查询单个模板
     */
    @GetMapping("/template/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','OPERATOR')")
    public Result<ImportTemplate> templateGet(@PathVariable Long id) {
        return Result.success(importTemplateService.getById(id));
    }

    /**
     * 按模板 ID 下载 Excel 模板文件（仅含表头）
     * <p>
     * 表头来自模板的 column_mapping JSON 的 value（中文名）。
     * 输出 .xlsx 二进制流，文件名格式：{templateName}-模板.xlsx
     */
    @GetMapping("/template/{id}/download")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','OPERATOR')")
    public void downloadTemplateById(@PathVariable Long id, HttpServletResponse response) {
        ImportTemplate template = importTemplateService.getById(id);
        if (template == null) {
            writeError(response, "模板不存在：id=" + id);
            return;
        }
        writeTemplateExcel(response, template);
    }

    /**
     * 按 sourceType 下载默认模板（取该来源下第一条启用的模板）
     * <p>
     * 例如 sourceType=PLATFORM / BANK / COST。
     * 文件名格式：{templateName}-模板.xlsx
     */
    @GetMapping("/template/download")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','OPERATOR')")
    public void downloadTemplateBySourceType(@RequestParam String sourceType, HttpServletResponse response) {
        ImportTemplate template = importTemplateService.getDefaultTemplate(sourceType);
        if (template == null) {
            writeError(response, "未找到 sourceType=" + sourceType + " 的默认模板");
            return;
        }
        writeTemplateExcel(response, template);
    }

    /**
     * 下载汇率导入模板（含表头 + 红色示例数据行）
     * <p>
     * 汇率导入不使用 ImportTemplate 表，直接生成模板。
     * 表头：日期、源币种、目标币种、汇率、来源
     */
    @GetMapping("/template/exchange-rate/download")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','OPERATOR')")
    public void downloadExchangeRateTemplate(HttpServletResponse response) {
        List<List<String>> headList = List.of(
                List.of("日期"), List.of("源币种"), List.of("目标币种"), List.of("汇率"), List.of("来源"));
        List<List<Object>> demoData = List.of(
                new ArrayList<>(Arrays.asList("2026-07-01", "USD", "CNY", "7.2500", "（示例数据，导入前请删除此行）")));
        writeExcelWithRedDemoRow(response, "汇率导入模板.xlsx", headList, demoData);
    }

    /**
     * 解析 column_mapping JSON 并写出含表头 + 红色示例数据行的 Excel 到 HttpServletResponse
     * <p>
     * 示例数据行使用红色字体，演示正确格式，用户导入前需删除该行。
     */
    private void writeTemplateExcel(HttpServletResponse response, ImportTemplate template) {
        Map<String, String> fieldToHeader = parseColumnMapping(template.getColumnMapping());
        if (fieldToHeader.isEmpty()) {
            writeError(response, "模板「" + template.getTemplateName() + "」字段映射为空，无法生成模板");
            return;
        }
        // EasyExcel head 结构：每个内层 List 代表一列的表头
        List<List<String>> headList = new ArrayList<>();
        for (String h : fieldToHeader.values()) {
            headList.add(List.of(h));
        }
        // 生成与表头顺序对应的示例数据行
        List<String> demoValues = new ArrayList<>();
        for (String fieldName : fieldToHeader.keySet()) {
            demoValues.add(getDemoValue(fieldName, template.getSourceType()));
        }
        List<List<Object>> demoData = List.of(new ArrayList<>(demoValues));

        String fileName = template.getTemplateName() + "-模板.xlsx";
        writeExcelWithRedDemoRow(response, fileName, headList, demoData);
    }

    /**
     * 写出 Excel：表头默认样式，示例数据行使用红色字体
     */
    private void writeExcelWithRedDemoRow(HttpServletResponse response, String fileName,
                                          List<List<String>> headList, List<List<Object>> demoData) {
        String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition",
                "attachment; filename*=UTF-8''" + encodedFileName);
        response.setHeader("Cache-Control", "no-cache");

        // 数据行样式：红色字体，提示用户这是示例数据
        WriteCellStyle dataStyle = new WriteCellStyle();
        WriteFont dataFont = new WriteFont();
        dataFont.setColor(IndexedColors.RED.getIndex());
        dataFont.setFontHeightInPoints((short) 11);
        dataStyle.setWriteFont(dataFont);
        HorizontalCellStyleStrategy styleStrategy = new HorizontalCellStyleStrategy(null, dataStyle);

        try {
            EasyExcel.write(response.getOutputStream())
                    .head(headList)
                    .registerWriteHandler(styleStrategy)
                    .sheet("模板")
                    .doWrite(demoData);
        } catch (IOException e) {
            log.error("[模板下载] 写出 Excel 失败 fileName={}", fileName, e);
            writeError(response, "下载模板失败：" + e.getMessage());
        }
    }

    /**
     * 根据字段名生成示例值（用于模板中的红色演示行）
     */
    private String getDemoValue(String fieldName, String sourceType) {
        if (fieldName == null) return "";
        return switch (fieldName) {
            case "orderNo" -> "ORD-20260701-001";
            case "platform" -> "Amazon";
            case "shopId" -> "SHOP001";
            case "currency", "fromCurrency" -> "USD";
            case "toCurrency" -> "CNY";
            case "amount" -> "1500.50";
            case "fee" -> "50.00";
            case "settleAmount" -> "10875.00";
            case "orderTime" -> "2026-07-01 10:00:00";
            case "settleTime" -> "2026-07-03 15:30:00";
            case "costType" -> "物流费";
            case "period" -> "202607";
            case "costDate", "rateDate" -> "2026-07-01";
            case "payee" -> "物流公司";
            case "rate" -> "7.2500";
            case "source" -> "示例数据";
            case "remark" -> "（示例数据，导入前请删除此行）";
            default -> "示例数据";
        };
    }

    /**
     * 解析 column_mapping JSON，返回有序的 LinkedHashMap（字段名 → 中文表头）
     * <p>
     * JSON 结构示例：{"orderNo":"订单号","amount":"金额"}
     * 使用 LinkedHashMap 保证顺序与 JSON 文本一致。
     */
    private Map<String, String> parseColumnMapping(String columnMapping) {
        if (columnMapping == null || columnMapping.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(
                    columnMapping, new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (Exception e) {
            log.warn("[模板下载] column_mapping 解析失败 raw={}", columnMapping, e);
            return Map.of();
        }
    }

    /**
     * 写出错误响应：500 状态 + JSON Result
     */
    private void writeError(HttpServletResponse response, String msg) {
        try {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(objectMapper.writeValueAsString(Result.error(msg)));
        } catch (IOException ignored) {
            // 忽略二次写入异常
        }
    }

    /**
     * 新增模板
     */
    @PostMapping("/template")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public Result<Long> templateSave(@RequestBody ImportTemplate template) {
        return Result.success(importTemplateService.save(template));
    }

    /**
     * 更新模板
     */
    @PutMapping("/template")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public Result<Void> templateUpdate(@RequestBody ImportTemplate template) {
        importTemplateService.update(template);
        return Result.success();
    }

    /**
     * 删除模板
     * <p>
     * 默认模板（is_default=1）不可删除。
     */
    @DeleteMapping("/template/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public Result<Void> templateDelete(@PathVariable Long id) {
        importTemplateService.delete(id);
        return Result.success();
    }

    /**
     * 上传自定义模板文件（Excel/CSV）
     * <p>
     * 解析文件表头行，按中文字段名智能匹配生成 column_mapping，保存为自定义模板（is_default=0）。
     * 默认模板仍保留，用户删除自定义模板后自动回退到默认模板。
     *
     * @param file         Excel(.xlsx/.xls) 或 CSV 文件（只需表头行）
     * @param sourceType   数据来源：PLATFORM/BANK/COST
     * @param templateName 模板名称（可选，空则用文件名）
     * @return 新建的模板 ID
     */
    @PostMapping("/template/upload")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public Result<Long> templateUpload(
            @RequestParam("file") MultipartFile file,
            @RequestParam String sourceType,
            @RequestParam(required = false) String templateName) {
        FileValidationUtils.validateExcelOrCsv(file);
        log.info("[模板上传] sourceType={}, fileName={}", sourceType, file.getOriginalFilename());
        Long id = importTemplateService.uploadTemplate(file, sourceType, templateName);
        return Result.success(id);
    }

    /**
     * 查询批次清洗错误明细
     */
    @GetMapping("/batch/{batchNo}/errors")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','OPERATOR')")
    public Result<ImportBatch> batchErrors(@PathVariable String batchNo) {
        return Result.success(importBatchService.getByBatchNo(batchNo));
    }

    // ==================== 额外费用导入接口 ====================

    /**
     * 上传 Excel/CSV 文件批量导入额外费用（物流/广告/仓储等）
     * <p>
     * 文件表头支持中英文命名（费用类型/金额/币种/核算周期/订单号/收款方/费用日期/备注），自动识别。
     * 费用类型列可填中文（如"物流费"）或英文编码（如"LOGISTICS"）。
     * 同一批次导入完成后自动按 costDate 当日汇率折算 CNY，供利润核算使用。
     *
     * @param file       Excel(.xlsx/.xls) 或 CSV 文件
     * @param templateId 导入模板 ID（可选，空则用 COST 默认模板）
     * @return 导入结果（成功/失败条数 + 失败明细）
     */
    @PostMapping("/cost")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public Result<ExtraCostService.ImportResult> importCost(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) Long templateId) {
        FileValidationUtils.validateExcelOrCsv(file);
        log.info("[ETL] 导入额外费用 file={}", file.getOriginalFilename());
        ExtraCostService.ImportResult result = extraCostService.importCosts(file, templateId);
        return Result.success(result);
    }

    /**
     * 分页查询额外费用记录
     */
    @GetMapping("/cost/page")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','OPERATOR')")
    public Result<Page<ExtraCost>> costPage(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String costType,
            @RequestParam(required = false) String orderNo) {
        return Result.success(extraCostService.page(page, size, period, costType, orderNo));
    }

    // ==================== 汇率批量导入接口 ====================

    /**
     * 上传 Excel/CSV 文件批量导入历史汇率
     * <p>
     * 文件表头支持中英文命名（日期/汇率日期/rate_date 等），自动识别。
     * 同日同币对已存在则覆盖更新；导入完成后自动刷新内存汇率缓存。
     *
     * @param file Excel(.xlsx/.xls) 或 CSV 文件
     * @return 导入结果（新增/更新/失败条数 + 失败明细）
     */
    @PostMapping("/exchange-rate")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public Result<ExchangeRateImportService.ImportResult> importExchangeRate(
            @RequestParam("file") MultipartFile file) {
        FileValidationUtils.validateExcelOrCsv(file);
        log.info("[ETL] 导入汇率文件 file={}", file.getOriginalFilename());
        ExchangeRateImportService.ImportResult result = exchangeRateImportService.importRates(file);
        return Result.success(result);
    }

    /**
     * 分页查询历史汇率记录
     */
    @GetMapping("/exchange-rate/page")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','OPERATOR')")
    public Result<IPage<ExchangeRateSnapshot>> exchangeRatePage(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String fromCurrency,
            @RequestParam(required = false) String toCurrency) {
        Page<ExchangeRateSnapshot> p = new Page<>(page, size);
        LambdaQueryWrapper<ExchangeRateSnapshot> wrapper = new LambdaQueryWrapper<ExchangeRateSnapshot>()
                .eq(fromCurrency != null && !fromCurrency.isBlank(),
                        ExchangeRateSnapshot::getFromCurrency, fromCurrency)
                .eq(toCurrency != null && !toCurrency.isBlank(),
                        ExchangeRateSnapshot::getToCurrency, toCurrency)
                .orderByDesc(ExchangeRateSnapshot::getRateDate);
        IPage<ExchangeRateSnapshot> result = exchangeRateService.page(p, wrapper);
        return Result.success(result);
    }

    /**
     * 自动补全缺失日期的汇率
     * <p>
     * 用缺失日期之前最近一个交易日的汇率填充，适用于汇率相对稳定的场景。
     * 若缺失日期之前无任何汇率记录，则尝试向后查找；若仍无，跳过该日期。
     *
     * @param fromCurrency 源币种（如 USD）
     * @param toCurrency   目标币种（如 CNY）
     * @param startDate    起始日期 yyyy-MM-dd（含）
     * @param endDate      结束日期 yyyy-MM-dd（含）
     * @return 实际补全的日期列表
     */
    @PostMapping("/exchange-rate/auto-fill")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public Result<AutoFillResult> autoFillExchangeRate(
            @RequestParam String fromCurrency,
            @RequestParam(defaultValue = "CNY") String toCurrency,
            @RequestParam String startDate,
            @RequestParam String endDate) {
        log.info("[ETL] 自动补全汇率 {}->{}, range={}~{}", fromCurrency, toCurrency, startDate, endDate);
        java.time.LocalDate start = java.time.LocalDate.parse(startDate);
        java.time.LocalDate end = java.time.LocalDate.parse(endDate);
        // 收集范围内缺失的日期
        List<ExchangeRateSnapshot> existing = exchangeRateService.list(
                new LambdaQueryWrapper<ExchangeRateSnapshot>()
                        .eq(ExchangeRateSnapshot::getFromCurrency, fromCurrency)
                        .eq(ExchangeRateSnapshot::getToCurrency, toCurrency)
                        .ge(ExchangeRateSnapshot::getRateDate, start)
                        .le(ExchangeRateSnapshot::getRateDate, end));
        java.util.Set<java.time.LocalDate> existingDates = new java.util.HashSet<>();
        for (ExchangeRateSnapshot s : existing) {
            existingDates.add(s.getRateDate());
        }
        List<java.time.LocalDate> missingDates = new java.util.ArrayList<>();
        java.time.LocalDate cursor = start;
        while (!cursor.isAfter(end)) {
            if (!existingDates.contains(cursor)) {
                missingDates.add(cursor);
            }
            cursor = cursor.plusDays(1);
        }
        if (missingDates.isEmpty()) {
            return Result.success(new AutoFillResult(0, 0, List.of()));
        }
        List<java.time.LocalDate> filled = exchangeRateService.autoFillMissingRates(
                fromCurrency, toCurrency, missingDates);
        int failed = missingDates.size() - filled.size();
        return Result.success(new AutoFillResult(missingDates.size(), filled.size(),
                filled.stream().map(java.time.LocalDate::toString).toList()));
    }

    /**
     * 自动补全结果
     *
     * @param missingCount 缺失天数
     * @param filledCount  实际补全天数
     * @param filledDates  实际补全的日期列表
     */
    public record AutoFillResult(
            int missingCount,
            int filledCount,
            List<String> filledDates
    ) {}
}
