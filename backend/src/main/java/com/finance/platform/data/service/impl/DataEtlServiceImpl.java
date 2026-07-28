package com.finance.platform.data.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.platform.common.constant.BusinessConstants;
import com.finance.platform.common.exception.BusinessException;
import com.finance.platform.data.entity.ExchangeRateSnapshot;
import com.finance.platform.data.entity.ImportBatch;
import com.finance.platform.data.entity.ImportTemplate;
import com.finance.platform.data.entity.RawOrder;
import com.finance.platform.data.etl.FailedRow;
import com.finance.platform.data.etl.FieldMappingException;
import com.finance.platform.data.etl.parser.FileParser;
import com.finance.platform.data.etl.parser.FileParserFactory;
import com.finance.platform.data.etl.parser.RawOrderRowMapper;
import com.finance.platform.data.etl.rule.CleanContext;
import com.finance.platform.data.etl.rule.CleanRule;
import com.finance.platform.data.etl.rule.CleanRuleChain;
import com.finance.platform.data.mapper.RawOrderMapper;
import com.finance.platform.data.service.DataEtlService;
import com.finance.platform.data.service.ExchangeRateService;
import com.finance.platform.data.service.ImportBatchService;
import com.finance.platform.data.service.ImportTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 数据 ETL 服务实现（重构版）
 * <p>
 * 方案 B：解析器抽象 + 字段映射配置化 + 清洗规则引擎 + 批次状态机
 * <p>
 * 替代原硬编码实现：
 * - 不再依赖 @ExcelProperty 注解，改用 ImportTemplate.columnMapping 动态映射
 * - 不再 if-else 清洗，改用 CleanRuleChain 责任链
 * - 不再逐条 updateById，改用 Db.updateBatchById 批量更新
 * - 不再覆盖 settleTime，新增 cleanTime 记录清洗时间
 * - 新增 ImportBatch 状态机：IMPORTED → CLEANING → CLEANED/FAILED
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataEtlServiceImpl implements DataEtlService {

    private final FileParserFactory fileParserFactory;
    private final RawOrderRowMapper rawOrderRowMapper;
    private final RawOrderMapper rawOrderMapper;
    private final ImportTemplateService importTemplateService;
    private final ImportBatchService importBatchService;
    private final CleanRuleChain cleanRuleChain;
    private final ObjectMapper objectMapper;
    private final ExchangeRateService exchangeRateService;

    @Lazy
    @Autowired
    private DataEtlService self;

    @Override
    public DataEtlService.BillImportResult importBill(MultipartFile file, String source, Long templateId) {
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
            template = importTemplateService.getDefaultTemplate(source);
            if (template == null) {
                throw new BusinessException("未找到数据来源[" + source + "]的默认导入模板");
            }
        }

        // 2. 解析文件（根据上传文件扩展名选择解析器，而非模板的 fileType，确保 CSV 文件用 CsvFileParser 解析）
        FileParser parser = fileParserFactory.getParserByFileName(file.getOriginalFilename());
        Map<String, String> columnMapping = parseColumnMapping(template.getColumnMapping());
        FileParser.ParseConfig config = new FileParser.ParseConfig(
                columnMapping, template.getHeaderRow(), template.getSheetNo());
        FileParser.ParseResult parseResult = parser.parse(file, config);

        log.info("[ETL] 文件 {} 解析完成，成功 {} 行，失败 {} 行，模板={}",
                file.getOriginalFilename(), parseResult.successRows().size(),
                parseResult.failedRows().size(), template.getTemplateName());

        // 3. 生成批次号
        String batchNo = IdUtil.simpleUUID();

        // 4. 创建批次记录
        importBatchService.create(batchNo, template.getId(), file.getOriginalFilename(), source);

        // 5. 转换为 RawOrder 实体并填充批次号/来源（严格模式：行级错误收集为 FailedRow）
        List<RawOrder> orders = new ArrayList<>();
        List<FailedRow> failedRows = new ArrayList<>();
        // 解析阶段失败行（来自 FileParser，如列数不匹配 / 空行等），转换为统一 FailedRow 结构
        for (FileParser.ParseResult.RowError re : parseResult.failedRows()) {
            failedRows.add(new FailedRow(
                    re.rowNo(),
                    "（整行解析）",
                    truncate(re.rawLine()),
                    re.reason(),
                    "请检查该行列数是否与表头一致，或是否存在空行 / 非法字符；可点击「下载模板」获取标准格式"
            ));
        }
        // 5.1 预查范围内已存在的订单号集合（跨批次去重，避免重复导入相同订单）
        java.util.Set<String> existingOrderNos = collectExistingOrderNos(parseResult.successRows(), source);
        int duplicateCount = 0;
        // 映射阶段失败行（来自 RawOrderRowMapper.mapStrict，必填字段为空 / 格式错误）
        int rowNo = 1; // 表头为第 1 行，数据行从第 2 行开始
        for (Map<String, String> row : parseResult.successRows()) {
            rowNo++;
            try {
                RawOrder order = rawOrderRowMapper.mapStrict(row);
                // 跨批次去重：按 (source, orderNo) 复合键判断
                if (StrUtil.isNotBlank(order.getOrderNo()) && existingOrderNos.contains(order.getOrderNo())) {
                    duplicateCount++;
                    failedRows.add(new FailedRow(
                            rowNo,
                            "订单号",
                            truncate(order.getOrderNo()),
                            "订单号已存在（跨批次重复）",
                            "该订单号在系统中已存在，已跳过导入；如需更新数据，请先在「数据管理」中删除原记录，再重新导入"
                    ));
                    continue;
                }
                order.setBatchNo(batchNo);
                if (StrUtil.isBlank(order.getSource())) {
                    order.setSource(source);
                }
                order.setCleanStatus(RawOrder.CLEAN_STATUS_NONE);
                order.setReconcileStatus(RawOrder.RECONCILE_NONE);
                orders.add(order);
                // 加入集合，防止本批次内重复
                if (StrUtil.isNotBlank(order.getOrderNo())) {
                    existingOrderNos.add(order.getOrderNo());
                }
            } catch (FieldMappingException e) {
                failedRows.add(new FailedRow(
                        rowNo,
                        e.getFieldName(),
                        e.getFieldValue(),
                        e.getMessage(),
                        e.getSuggestion()
                ));
            } catch (Exception e) {
                // 兜底：未预期的异常也记入失败行，避免单行异常拖垮整批导入
                failedRows.add(new FailedRow(
                        rowNo,
                        "（未知字段）",
                        "",
                        "映射异常：" + e.getMessage(),
                        "请联系管理员排查；可临时跳过该行后重新导入其他行"
                ));
            }
        }

        // 6. 记录失败行明细（在异步入库前设置，避免 cleanData 读到 null errorDetail）
        if (!failedRows.isEmpty()) {
            try {
                String errorDetail = objectMapper.writeValueAsString(failedRows);
                ImportBatch batch = importBatchService.getByBatchNo(batchNo);
                if (batch != null) {
                    batch.setErrorDetail(errorDetail);
                    batch.setFailedCount(failedRows.size());
                    importBatchService.update(batch);
                }
            } catch (Exception e) {
                log.warn("[ETL] 序列化失败行明细异常：{}", e.getMessage());
            }
        }

        // 7. 异步入库（入库完成后自动触发清洗）
        if (!orders.isEmpty()) {
            self.asyncSaveBatch(orders, batchNo, source);
        }
        importBatchService.updateTotalCount(batchNo, orders.size());

        if (duplicateCount > 0) {
            log.info("[ETL] 批次 {} 跳过 {} 条跨批次重复订单", batchNo, duplicateCount);
        }
        int totalRows = parseResult.successRows().size();
        boolean wholeTableDuplicate = duplicateCount > 0 && orders.isEmpty();
        log.info("[ETL] 批次 {} 导入完成，总 {} 条，成功 {} 条，失败 {} 条（含重复 {} 条），整表重复={}",
                batchNo, totalRows, orders.size(), failedRows.size(), duplicateCount, wholeTableDuplicate);
        return new DataEtlService.BillImportResult(
                batchNo, totalRows, orders.size(), failedRows.size(), duplicateCount, wholeTableDuplicate);
    }

    /**
     * 收集本次导入文件中涉及的所有订单号，并查询数据库中已存在的订单号（跨批次去重用）。
     * 仅查询本次文件中出现的订单号，避免全表扫描影响性能。
     */
    private java.util.Set<String> collectExistingOrderNos(List<Map<String, String>> rows, String source) {
        java.util.Set<String> orderNos = new java.util.HashSet<>();
        for (Map<String, String> row : rows) {
            String v = row.get("orderNo");
            if (v != null && !v.isBlank()) {
                orderNos.add(v.trim());
            }
        }
        if (orderNos.isEmpty()) {
            return java.util.Collections.emptySet();
        }
        // 批量查询已存在的订单号（按 source 过滤）
        List<RawOrder> existing = rawOrderMapper.selectList(new LambdaQueryWrapper<RawOrder>()
                .eq(RawOrder::getSource, source)
                .in(RawOrder::getOrderNo, orderNos)
                .select(RawOrder::getOrderNo));
        java.util.Set<String> existingSet = new java.util.HashSet<>();
        for (RawOrder o : existing) {
            if (o.getOrderNo() != null) {
                existingSet.add(o.getOrderNo());
            }
        }
        return existingSet;
    }

    /** 截断过长的原始行内容，避免错误明细 UI 失控 */
    private String truncate(String v) {
        if (v == null) return "";
        return v.length() > 80 ? v.substring(0, 80) + "..." : v;
    }

    @Async("etlExecutor")
    @Override
    public void asyncSaveBatch(List<RawOrder> orders, String batchNo, String source) {
        com.baomidou.mybatisplus.extension.toolkit.Db.saveBatch(orders);
        log.info("[ETL] 批次 {} 入库 {} 条, source={}", batchNo, orders.size(), source);
        // 入库完成后自动触发清洗（通过 self 代理调用，确保 @Transactional 生效）
        try {
            self.cleanData(batchNo);
            log.info("[ETL] 批次 {} 自动清洗完成", batchNo);
        } catch (Exception e) {
            log.error("[ETL] 批次 {} 自动清洗失败", batchNo, e);
            // 清洗失败不影响导入结果，用户可手动重新清洗
        }
    }

    @Override
    public void cleanData(String batchNo) {
        if (StrUtil.isBlank(batchNo)) {
            throw new BusinessException("批次号不能为空");
        }

        // 1. 查询批次，获取模板配置
        ImportBatch batch = importBatchService.getByBatchNo(batchNo);
        if (batch == null) {
            throw new BusinessException("批次不存在：" + batchNo);
        }
        ImportTemplate template = batch.getTemplateId() != null
                ? importTemplateService.getById(batch.getTemplateId()) : null;
        if (template == null) {
            throw new BusinessException("批次关联的导入模板不存在");
        }

        // 2. 标记清洗中
        importBatchService.markCleaning(batchNo);

        try {
            // 3. 查询批次数据
            List<RawOrder> list = rawOrderMapper.selectList(new LambdaQueryWrapper<RawOrder>()
                    .eq(RawOrder::getBatchNo, batchNo));
            if (list.isEmpty()) {
                log.warn("[ETL] 清洗批次 {} 无数据", batchNo);
                importBatchService.markCleaned(batchNo, 0, 0, null);
                return;
            }

            // 3.5 预检查汇率：收集所有非 CNY 币种，检查是否有对应汇率记录
            checkExchangeRatesBeforeClean(list);

            // 4. 构建规则链
            List<CleanRule> chain = cleanRuleChain.buildChain(template.getCleanRules());
            log.info("[ETL] 批次 {} 启用清洗规则：{}", batchNo,
                    chain.stream().map(CleanRule::ruleName).toList());

            // 5. 逐条应用规则链
            CleanContext context = new CleanContext(batchNo);
            int successCount = 0;
            int failedCount = 0;
            List<Map<String, Object>> errorDetails = new ArrayList<>();

            for (RawOrder order : list) {
                CleanRule.CleanResult result = cleanRuleChain.applyAll(chain, order, context);
                if (result.ok()) {
                    order.setCleanStatus(RawOrder.CLEAN_STATUS_DONE);
                    order.setCleanErrors(null);
                    order.setCleanTime(LocalDateTime.now());
                    successCount++;
                } else {
                    order.setCleanStatus(RawOrder.CLEAN_STATUS_FAIL);
                    order.setCleanErrors(result.reason());
                    failedCount++;
                    errorDetails.add(Map.of(
                            "orderNo", order.getOrderNo() == null ? "" : order.getOrderNo(),
                            "reason", result.reason()
                    ));
                }
            }

            // 6. 批量更新（替代原逐条 updateById）
            com.baomidou.mybatisplus.extension.toolkit.Db.updateBatchById(list);

            // 7. 更新批次状态（保留导入阶段的 errorDetail，新增 cleanSummary 记录清洗汇总）
            //    errorDetail 存导入失败明细（importBill 阶段设置），cleanData 不覆盖
            ImportBatch existingBatch = importBatchService.getByBatchNo(batchNo);
            String preservedErrorDetail = existingBatch != null ? existingBatch.getErrorDetail() : null;
            String cleanSummaryJson = buildCleanSummary(context);
            importBatchService.markCleaned(batchNo, successCount, failedCount, preservedErrorDetail, cleanSummaryJson);

            log.info("[ETL] 批次 {} 清洗完成，成功 {} 条，失败 {} 条，清洗动作 {} 条",
                    batchNo, successCount, failedCount, context.getActions().size());
        } catch (Exception e) {
            log.error("[ETL] 批次 {} 清洗失败", batchNo, e);
            importBatchService.markFailed(batchNo, e.getMessage());
            throw new BusinessException("清洗失败：" + e.getMessage());
        }
    }

    /**
     * 构建清洗汇总 JSON：按规则分组统计，记录每条动作的描述
     * <p>
     * 格式统一（TrimRule）不记录动作，其他规则（异常拦截/缺省补全/汇率折算/去重）触发的动作都会记录。
     *
     * @return JSON 字符串，无动作时返回 null
     */
    private String buildCleanSummary(CleanContext context) {
        List<CleanContext.CleanAction> actions = context.getActions();
        if (actions.isEmpty()) return null;
        try {
            // 按规则分组汇总
            Map<String, Long> countByRule = actions.stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            CleanContext.CleanAction::ruleName,
                            java.util.stream.Collectors.counting()));
            // 构建汇总列表
            List<Map<String, Object>> summary = new ArrayList<>();
            for (Map.Entry<String, Long> entry : countByRule.entrySet()) {
                String ruleName = entry.getKey();
                long count = entry.getValue();
                String displayName = RULE_DISPLAY_NAMES.getOrDefault(ruleName, ruleName);
                // 取该规则的第一条描述作为示例
                String sampleDesc = actions.stream()
                        .filter(a -> a.ruleName().equals(ruleName))
                        .map(CleanContext.CleanAction::description)
                        .findFirst().orElse("");
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("ruleName", ruleName);
                item.put("displayName", displayName);
                item.put("count", count);
                item.put("sampleDescription", sampleDesc);
                summary.add(item);
            }
            // 同时保留完整动作明细（含订单号），前端可选择展示
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("summary", summary);
            result.put("details", actions.stream().map(a -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ruleName", a.ruleName());
                m.put("displayName", RULE_DISPLAY_NAMES.getOrDefault(a.ruleName(), a.ruleName()));
                m.put("description", a.description());
                return m;
            }).toList());
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.warn("[ETL] 构建清洗汇总异常：{}", e.getMessage());
            return null;
        }
    }

    /** 规则显示名称映射（用于前端展示） */
    private static final Map<String, String> RULE_DISPLAY_NAMES = Map.of(
            "filterInvalidRule", "异常拦截",
            "defaultCurrencyRule", "缺省币种补全",
            "currencyConvertRule", "汇率折算",
            "deduplicateRule", "去重"
    );

    /**
     * 清洗前预检查汇率：收集所有非 CNY 币种，检查数据库是否有对应的汇率记录。
     * 若有币种完全缺失汇率记录，抛出 BusinessException 中止清洗，提示用户先导入汇率。
     */
    private void checkExchangeRatesBeforeClean(List<RawOrder> list) {
        // 收集所有非 CNY 币种
        Set<String> foreignCurrencies = new LinkedHashSet<>();
        for (RawOrder order : list) {
            String c = order.getCurrency();
            if (StrUtil.isNotBlank(c) && !BusinessConstants.CURRENCY_CNY.equals(c)) {
                foreignCurrencies.add(c);
            }
        }
        if (foreignCurrencies.isEmpty()) return;

        // 逐个币种检查是否有汇率记录
        List<String> missing = new ArrayList<>();
        for (String currency : foreignCurrencies) {
            long count = exchangeRateService.count(new LambdaQueryWrapper<ExchangeRateSnapshot>()
                    .eq(ExchangeRateSnapshot::getFromCurrency, currency)
                    .eq(ExchangeRateSnapshot::getToCurrency, BusinessConstants.CURRENCY_CNY));
            if (count == 0) {
                missing.add(currency);
            }
        }

        if (!missing.isEmpty()) {
            throw new BusinessException("清洗中止：以下币种缺少汇率记录：" + String.join("、", missing)
                    + " -> CNY。请先在「历史汇率导入」页导入对应币种的汇率后再清洗。");
        }
    }

    @Override
    public void reconcileBankFlow(String batchNo) {
        log.info("[ETL] 银行流水对账开始 batchNo={}", batchNo);
        List<RawOrder> bankFlows = rawOrderMapper.selectList(new LambdaQueryWrapper<RawOrder>()
                .eq(RawOrder::getBatchNo, batchNo)
                .eq(RawOrder::getSource, BusinessConstants.SOURCE_BANK));
        // TODO: 与平台订单逐笔匹配，标记对账状态（一致/差异/缺失）
        log.info("[ETL] 银行流水对账完成 batchNo={}, 记录数={}", batchNo, bankFlows.size());
    }

    private Map<String, String> parseColumnMapping(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new BusinessException("字段映射 JSON 解析失败：" + e.getMessage());
        }
    }
}
