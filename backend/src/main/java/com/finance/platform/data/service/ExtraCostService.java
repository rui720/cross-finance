package com.finance.platform.data.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.finance.platform.common.constant.BusinessConstants;
import com.finance.platform.common.exception.BusinessException;
import com.finance.platform.common.utils.CurrencyConvertUtils;
import com.finance.platform.common.utils.ImportFieldNormalizer;
import com.finance.platform.data.entity.CostType;
import com.finance.platform.data.entity.ExtraCost;
import com.finance.platform.data.entity.ImportBatch;
import com.finance.platform.data.entity.ImportTemplate;
import com.finance.platform.data.etl.FailedRow;
import com.finance.platform.data.etl.FieldMappingException;
import com.finance.platform.data.etl.parser.FileParser;
import com.finance.platform.data.etl.parser.FileParserFactory;
import com.finance.platform.data.mapper.ExtraCostMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 额外费用服务
 * <p>
 * 支持物流费、广告费、仓储费等额外费用的批量导入与查询。
 * 导入流程复用方案 B 的 {@link FileParserFactory} 解析器抽象：
 * <ol>
 *   <li>按 source_type=COST 获取默认模板</li>
 *   <li>解析 Excel/CSV 文件为 Map 行数据</li>
 *   <li>逐行映射为 {@link ExtraCost} 实体，校验费用类型、币种、金额</li>
 *   <li>按 costDate 当日汇率折算 cnyAmount</li>
 *   <li>批量入库，并创建 {@link ImportBatch} 批次记录</li>
 * </ol>
 * 利润核算时由 {@code ProfitEngineServiceImpl} 读取本表数据参与分摊。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExtraCostService extends ServiceImpl<ExtraCostMapper, ExtraCost> {

    private final FileParserFactory fileParserFactory;
    private final ImportTemplateService importTemplateService;
    private final ImportBatchService importBatchService;
    private final CurrencyConvertUtils currencyConvertUtils;
    private final ObjectMapper objectMapper;

    /**
     * 批量导入额外费用文件
     *
     * @param file       Excel/CSV 文件
     * @param templateId 导入模板 ID（可选，空则用 COST 默认模板）
     * @return 导入结果
     */
    public ImportResult importCosts(MultipartFile file, Long templateId) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件不能为空");
        }

        // 1. 获取导入模板
        ImportTemplate template;
        if (templateId != null) {
            template = importTemplateService.getById(templateId);
            if (template == null) {
                throw new BusinessException("导入模板不存在：" + templateId);
            }
        } else {
            template = importTemplateService.getDefaultTemplate(BusinessConstants.SOURCE_COST);
            if (template == null) {
                throw new BusinessException("未找到额外费用默认导入模板，请先在模板管理中创建 source_type=COST 的模板");
            }
        }

        // 2. 解析文件（根据上传文件扩展名选择解析器，而非模板的 fileType，确保 CSV 文件用 CsvFileParser 解析）
        FileParser parser = fileParserFactory.getParserByFileName(file.getOriginalFilename());
        Map<String, String> columnMapping = parseColumnMapping(template.getColumnMapping());
        FileParser.ParseConfig config = new FileParser.ParseConfig(
                columnMapping, template.getHeaderRow(), template.getSheetNo());
        FileParser.ParseResult parseResult = parser.parse(file, config);

        log.info("[ExtraCost] 文件 {} 解析完成，成功 {} 行，失败 {} 行",
                file.getOriginalFilename(), parseResult.successRows().size(),
                parseResult.failedRows().size());

        // 3. 生成批次号并创建批次记录
        String batchNo = IdUtil.simpleUUID();
        importBatchService.create(batchNo, template.getId(), file.getOriginalFilename(),
                BusinessConstants.SOURCE_COST);

        // 4. 逐行映射为 ExtraCost 实体并折算 CNY（统一 FailedRow 结构收集错误）
        List<ExtraCost> successList = new ArrayList<>();
        List<FailedRow> failedRows = new ArrayList<>();
        // 解析阶段失败行（来自 FileParser，如列数不匹配 / 空行等）
        for (FileParser.ParseResult.RowError re : parseResult.failedRows()) {
            failedRows.add(new FailedRow(
                    re.rowNo(),
                    "（整行解析）",
                    ImportFieldNormalizer.truncate(re.rawLine()),
                    re.reason(),
                    "请检查该行列数是否与表头一致，或是否存在空行 / 非法字符；可点击「下载模板」获取标准格式"
            ));
        }
        // 4.1 预查范围内已存在的额外费用键集合（跨批次去重）
        java.util.Set<String> existingKeys = collectExistingExtraCostKeys(parseResult.successRows());
        int duplicateCount = 0;
        // 映射阶段失败行
        int rowNo = 1;
        for (Map<String, String> row : parseResult.successRows()) {
            rowNo++;
            try {
                ExtraCost cost = mapRow(row);
                // 跨批次去重：按 (orderNo, costType, costDate, period) 复合键判断
                String dedupKey = buildExtraCostDedupKey(cost);
                if (existingKeys.contains(dedupKey)) {
                    duplicateCount++;
                    failedRows.add(new FailedRow(
                            rowNo,
                            "（整行重复）",
                            dedupKey,
                            "额外费用记录已存在（跨批次重复）",
                            "该 (订单号+费用类型+费用日期+核算周期) 组合在系统中已存在，已跳过导入；如需更新数据，请先在「数据管理」中删除原记录，再重新导入"
                    ));
                    continue;
                }
                cost.setBatchNo(batchNo);
                cost.setSource("IMPORT");
                cost.setStatus(1);
                // 按 costDate 当日汇率折算 CNY（若折算失败则用原值，币种为 CNY 时直接等于 amount）
                cost.setCnyAmount(convertToCny(cost.getAmount(), cost.getCurrency()));
                successList.add(cost);
                existingKeys.add(dedupKey);
            } catch (FieldMappingException e) {
                failedRows.add(new FailedRow(
                        rowNo,
                        e.getFieldName(),
                        e.getFieldValue(),
                        e.getMessage(),
                        e.getSuggestion()
                ));
            } catch (Exception e) {
                failedRows.add(new FailedRow(
                        rowNo,
                        "（未知字段）",
                        "",
                        "映射异常：" + e.getMessage(),
                        "请联系管理员排查；可临时跳过该行后重新导入其他行"
                ));
            }
        }

        // 5. 批量入库
        if (!successList.isEmpty()) {
            saveBatch(successList);
        }
        importBatchService.updateTotalCount(batchNo, successList.size());

        // 6. 序列化失败行明细（直接传给 markCleaned，避免被覆盖）
        String errorDetailJson = null;
        if (!failedRows.isEmpty()) {
            try {
                errorDetailJson = objectMapper.writeValueAsString(failedRows);
            } catch (Exception e) {
                log.warn("[ExtraCost] 序列化失败行明细异常：{}", e.getMessage());
            }
        }

        // 7. 标记批次完成（额外费用不需要清洗步骤，直接 CLEANED；errorDetail 一并写入）
        importBatchService.markCleaned(batchNo, successList.size(), failedRows.size(), errorDetailJson);

        log.info("[ExtraCost] 批次 {} 导入完成，成功 {} 条，失败 {} 条",
                batchNo, successList.size(), failedRows.size());

        return new ImportResult(batchNo, successList.size(), failedRows.size(), failedRows);
    }

    /**
     * 分页查询额外费用
     */
    public Page<ExtraCost> page(long page, long size, String period, String costType, String orderNo) {
        Page<ExtraCost> p = new Page<>(page, size);
        LambdaQueryWrapper<ExtraCost> wrapper = new LambdaQueryWrapper<ExtraCost>()
                .eq(StrUtil.isNotBlank(period), ExtraCost::getPeriod, period)
                .eq(StrUtil.isNotBlank(costType), ExtraCost::getCostType, costType)
                .eq(StrUtil.isNotBlank(orderNo), ExtraCost::getOrderNo, orderNo)
                .orderByDesc(ExtraCost::getCostDate)
                .orderByDesc(ExtraCost::getId);
        return page(p, wrapper);
    }

    /**
     * 查询周期内全部生效的额外费用（供利润核算引擎调用）
     */
    public List<ExtraCost> listByPeriod(String period) {
        return list(new LambdaQueryWrapper<ExtraCost>()
                .eq(ExtraCost::getPeriod, period)
                .eq(ExtraCost::getStatus, 1));
    }

    // ==================== 内部方法 ====================

    private ExtraCost mapRow(Map<String, String> row) {
        ExtraCost cost = new ExtraCost();

        // 费用类型（必填，支持中文/英文编码）
        String costTypeText = getStr(row, "costType");
        if (StrUtil.isBlank(costTypeText)) {
            throw new FieldMappingException("费用类型", "",
                    "费用类型为空",
                    "费用类型是必填字段，支持中文（物流费/仓储费/广告费/关税税费/平台佣金/汇兑损失/退货损失/手续费/中转手续费/包装费/其他）或英文编码（LOGISTICS/WAREHOUSE/ADVERTISING 等）");
        }
        CostType costType = CostType.fromText(costTypeText);
        if (costType == null) {
            throw new FieldMappingException("费用类型", ImportFieldNormalizer.truncate(costTypeText),
                    "无法识别的费用类型：" + ImportFieldNormalizer.truncate(costTypeText),
                    "请使用支持的类型：物流费/仓储费/广告费/关税税费/平台佣金/汇兑损失/退货损失/手续费/中转手续费/包装费/其他，或对应英文编码");
        }
        cost.setCostType(costType.name());

        // 金额（必填，复用统一解析工具：支持千分位逗号、货币符号、中文单位、括号负数）
        String amountRaw = row.get("amount");
        if (amountRaw == null || amountRaw.isBlank()) {
            throw new FieldMappingException("金额", "",
                    "金额为空", "金额是必填字段，请检查 amount 列是否有值");
        }
        BigDecimal amount = ImportFieldNormalizer.parseDecimal(amountRaw);
        if (amount == null) {
            throw new FieldMappingException("金额", ImportFieldNormalizer.truncate(amountRaw),
                    "金额格式错误：" + ImportFieldNormalizer.truncate(amountRaw),
                    "请检查 amount 列是否为纯数字；可包含小数点与千分位逗号（如 1,500.00），但不要包含文字、单位或货币符号");
        }
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new FieldMappingException("金额", ImportFieldNormalizer.truncate(amountRaw),
                    "金额不能为负数：" + amount,
                    "请检查 amount 列是否误填了负号；如为退款/退货损失，请使用对应的费用类型（如 RETURN_LOSS）");
        }
        cost.setAmount(amount);

        // 币种（默认 CNY，复用统一标准化工具：支持中文/英文/全角字符）
        String currencyRaw = getStr(row, "currency");
        String currency;
        if (StrUtil.isBlank(currencyRaw)) {
            currency = BusinessConstants.CURRENCY_CNY;
        } else {
            currency = ImportFieldNormalizer.normalizeCurrency(currencyRaw);
            if (currency == null) {
                throw new FieldMappingException("币种", ImportFieldNormalizer.truncate(currencyRaw),
                        "无法识别的币种：" + ImportFieldNormalizer.truncate(currencyRaw),
                        "请使用标准币种代码：CNY/USD/EUR/HKD/JPY/GBP/AUD/CAD/SGD 等；或中文：人民币/美元/欧元/港币/日元");
            }
        }
        cost.setCurrency(currency);

        // 核算周期（必填）
        String period = getStr(row, "period");
        if (StrUtil.isBlank(period)) {
            throw new FieldMappingException("核算周期", "",
                    "核算周期为空", "核算周期是必填字段（如 202607），请检查 period 列是否有值");
        }
        if (!period.matches("^\\d{6}$")) {
            throw new FieldMappingException("核算周期", ImportFieldNormalizer.truncate(period),
                    "核算周期格式错误：" + ImportFieldNormalizer.truncate(period),
                    "核算周期应为 6 位数字 YYYYMM（如 202607）；请检查是否误填了分隔符（如 2026-07）");
        }
        cost.setPeriod(period);

        // 订单号（可选）
        cost.setOrderNo(getStr(row, "orderNo"));

        // 收款方（可选）
        cost.setPayee(getStr(row, "payee"));

        // 费用日期（必填，复用统一解析工具：支持多种日期格式与单位数月/日）
        String costDateRaw = row.get("costDate");
        if (costDateRaw == null || costDateRaw.isBlank()) {
            throw new FieldMappingException("费用日期", "",
                    "费用日期为空", "费用日期是必填字段，请检查 costDate 列是否有值");
        }
        LocalDate costDate = ImportFieldNormalizer.parseDate(costDateRaw);
        if (costDate == null) {
            throw new FieldMappingException("费用日期", ImportFieldNormalizer.truncate(costDateRaw),
                    "费用日期格式错误：" + ImportFieldNormalizer.truncate(costDateRaw),
                    "支持格式：yyyy-MM-dd、yyyy/MM/dd、yyyy.MM.dd、yyyyMMdd 及单位数月/日（如 2026/7/31）；请检查是否包含非日期字符");
        }
        cost.setCostDate(costDate);

        // 备注（可选）
        cost.setRemark(getStr(row, "remark"));

        return cost;
    }

    /**
     * 构建额外费用去重键：(orderNo|costType|costDate|period)
     * orderNo 为空时用 "PUBLIC" 占位（公共池费用按 costType+costDate+period 去重）
     */
    private String buildExtraCostDedupKey(ExtraCost cost) {
        String orderNo = StrUtil.isBlank(cost.getOrderNo()) ? "PUBLIC" : cost.getOrderNo().trim();
        return orderNo + "|" + cost.getCostType() + "|" + cost.getCostDate() + "|" + cost.getPeriod();
    }

    /**
     * 预查询本次导入文件涉及的 (costType, costDate, period) 组合在数据库已存在的去重键集合。
     * 因为 orderNo 可能为空（公共池），不能简单按 orderNo 批量查询。
     * 这里采用按 costType + period 范围查询，再在内存中构建去重键集合。
     */
    private java.util.Set<String> collectExistingExtraCostKeys(List<Map<String, String>> rows) {
        // 收集本次文件涉及的所有 (costType, period) 组合
        java.util.Map<String, java.util.Set<String>> typePeriodMap = new java.util.HashMap<>();
        for (Map<String, String> row : rows) {
            String costType = row.get("costType");
            String period = row.get("period");
            if (StrUtil.isBlank(costType) || StrUtil.isBlank(period)) continue;
            // 转换为枚举名（与数据库存储一致）
            CostType ct = CostType.fromText(costType.trim());
            if (ct == null) continue;
            typePeriodMap.computeIfAbsent(ct.name(), k -> new java.util.HashSet<>()).add(period.trim());
        }
        if (typePeriodMap.isEmpty()) {
            return java.util.Collections.emptySet();
        }
        java.util.Set<String> existingKeys = new java.util.HashSet<>();
        // 按 costType + period 批量查询
        for (Map.Entry<String, java.util.Set<String>> entry : typePeriodMap.entrySet()) {
            String costType = entry.getKey();
            List<ExtraCost> existing = list(new LambdaQueryWrapper<ExtraCost>()
                    .eq(ExtraCost::getCostType, costType)
                    .in(ExtraCost::getPeriod, entry.getValue())
                    .eq(ExtraCost::getStatus, 1));
            for (ExtraCost c : existing) {
                existingKeys.add(buildExtraCostDedupKey(c));
            }
        }
        return existingKeys;
    }

    private BigDecimal convertToCny(BigDecimal amount, String currency) {
        if (BusinessConstants.CURRENCY_CNY.equals(currency)) {
            return amount;
        }
        try {
            return currencyConvertUtils.convert(amount, currency, BusinessConstants.CURRENCY_CNY);
        } catch (Exception e) {
            log.warn("[ExtraCost] 币种 {} 折算 CNY 失败：{}，按原值入库", currency, e.getMessage());
            return amount;
        }
    }

    private Map<String, String> parseColumnMapping(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new BusinessException("字段映射 JSON 解析失败：" + e.getMessage());
        }
    }

    private String getStr(Map<String, String> row, String key) {
        String v = row.get(key);
        return v == null ? null : v.trim();
    }

    /**
     * 导入结果
     */
    public record ImportResult(
            String batchNo,
            int successCount,
            int failedCount,
            List<FailedRow> failedRows
    ) {}
}
