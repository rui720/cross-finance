package com.finance.platform.data.service;

import cn.hutool.core.util.IdUtil;
import com.finance.platform.common.constant.BusinessConstants;
import com.finance.platform.common.utils.ImportFieldNormalizer;
import com.finance.platform.data.entity.ExchangeRateSnapshot;
import com.finance.platform.data.entity.ImportBatch;
import com.finance.platform.data.etl.parser.FileParser;
import com.finance.platform.data.etl.parser.FileParserFactory;
import com.finance.platform.data.mapper.ExchangeRateSnapshotMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 汇率批量导入服务
 * <p>
 * 通过 Excel/CSV 文件批量导入历史汇率，避免手工编写 SQL 脚本。
 * 复用方案 B 的 {@link FileParserFactory} 解析器抽象，支持任意列名映射。
 * <p>
 * 默认期望列（中文表头常见命名）：
 * <pre>
 * 日期 / 汇率日期 / rate_date  → rateDate
 * 源币种 / 币种 / from_currency → fromCurrency
 * 目标币种 / to_currency        → toCurrency
 * 汇率 / rate                   → rate
 * 来源 / source                 → source（可选，默认"批量导入"）
 * </pre>
 * <p>
 * 导入完成后自动刷新内存汇率缓存，使新汇率立即生效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeRateImportService {

    private final FileParserFactory fileParserFactory;
    private final ExchangeRateSnapshotMapper exchangeRateSnapshotMapper;
    private final ExchangeRateService exchangeRateService;
    private final ImportBatchService importBatchService;

    /** 默认字段映射：目标字段 → 表头别名（按常用命名列举，解析时取首个匹配） */
    private static final Map<String, List<String>> DEFAULT_ALIASES = Map.of(
            "rateDate",     List.of("日期", "汇率日期", "rate_date", "rateDate", "date"),
            "fromCurrency", List.of("源币种", "币种", "from_currency", "fromCurrency", "currency"),
            "toCurrency",   List.of("目标币种", "to_currency", "toCurrency"),
            "rate",         List.of("汇率", "rate"),
            "source",       List.of("来源", "source")
    );

    private static final String DEFAULT_SOURCE = "批量导入";

    /**
     * 导入汇率文件
     * <p>
     * 创建 import_batch 批次记录（sourceType=EXCHANGE_RATE），便于在导入页面统一展示批次列表和错误明细。
     * 汇率导入不需要清洗步骤，直接标记为 CLEANED。
     *
     * @param file Excel/CSV 文件
     * @return 导入结果
     */
    public ImportResult importRates(MultipartFile file) {
        log.info("[ExchangeRateImport] 开始导入汇率文件：{}", file.getOriginalFilename());

        // 1. 选择解析器，先读取表头
        FileParser parser = fileParserFactory.getParserByFileName(file.getOriginalFilename());
        Map<String, Object> head = parser.readHeadAndSamples(file, 0);
        @SuppressWarnings("unchecked")
        List<String> headers = (List<String>) head.getOrDefault("headers", List.of());

        // 2. 根据表头动态构建字段映射
        Map<String, String> columnMapping = buildColumnMapping(headers);
        if (columnMapping.isEmpty()) {
            throw new IllegalArgumentException("未能识别任何汇率字段，请检查表头是否包含：日期/源币种/目标币种/汇率");
        }
        log.info("[ExchangeRateImport] 识别字段映射：{}", columnMapping);

        // 3. 生成批次号并创建批次记录（sourceType=EXCHANGE_RATE）
        String batchNo = IdUtil.simpleUUID();
        importBatchService.create(batchNo, null, file.getOriginalFilename(),
                BusinessConstants.SOURCE_EXCHANGE_RATE);

        // 4. 用映射解析整个文件
        FileParser.ParseConfig mappedConfig = new FileParser.ParseConfig(columnMapping, 1, 0);
        FileParser.ParseResult mapped = parser.parse(file, mappedConfig);

        // 5. 行数据转实体并校验
        List<ExchangeRateSnapshot> validList = new ArrayList<>();
        List<FileParser.ParseResult.RowError> errors = new ArrayList<>(mapped.failedRows());
        int rowNo = 1;
        for (Map<String, String> row : mapped.successRows()) {
            rowNo++;
            try {
                ExchangeRateSnapshot snapshot = mapRow(row);
                if (snapshot == null) {
                    errors.add(new FileParser.ParseResult.RowError(rowNo, row.toString(), "关键字段缺失"));
                    continue;
                }
                validList.add(snapshot);
            } catch (Exception e) {
                errors.add(new FileParser.ParseResult.RowError(rowNo, row.toString(), e.getMessage()));
            }
        }

        // 6. 批量入库（按 rateDate + from + to 去重：同日同币对覆盖更新）
        // 优化：一次性查出文件涉及的所有 (rateDate, from, to) 组合已存在记录，构建索引避免 N+1 查询
        int inserted = 0;
        int updated = 0;
        if (!validList.isEmpty()) {
            // 收集文件涉及的所有日期、币种对，用于一次性查询
            java.util.Set<LocalDate> dates = new java.util.HashSet<>();
            java.util.Set<String> fromCurrencies = new java.util.HashSet<>();
            java.util.Set<String> toCurrencies = new java.util.HashSet<>();
            for (ExchangeRateSnapshot snap : validList) {
                dates.add(snap.getRateDate());
                fromCurrencies.add(snap.getFromCurrency());
                toCurrencies.add(snap.getToCurrency());
            }
            List<ExchangeRateSnapshot> existingList = exchangeRateSnapshotMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ExchangeRateSnapshot>()
                            .in(ExchangeRateSnapshot::getRateDate, dates)
                            .in(ExchangeRateSnapshot::getFromCurrency, fromCurrencies)
                            .in(ExchangeRateSnapshot::getToCurrency, toCurrencies));
            // 构建 (rateDate|from|to) → existing 索引
            Map<String, ExchangeRateSnapshot> existingIndex = new HashMap<>();
            for (ExchangeRateSnapshot e : existingList) {
                existingIndex.put(buildRateKey(e.getRateDate(), e.getFromCurrency(), e.getToCurrency()), e);
            }
            // 拆分为 toInsert / toUpdate 两个列表
            List<ExchangeRateSnapshot> toInsert = new ArrayList<>();
            List<ExchangeRateSnapshot> toUpdate = new ArrayList<>();
            for (ExchangeRateSnapshot snap : validList) {
                String key = buildRateKey(snap.getRateDate(), snap.getFromCurrency(), snap.getToCurrency());
                ExchangeRateSnapshot existing = existingIndex.get(key);
                if (existing == null) {
                    toInsert.add(snap);
                } else {
                    existing.setRate(snap.getRate());
                    existing.setSource(snap.getSource());
                    toUpdate.add(existing);
                }
            }
            // 批量插入：用 IService.saveBatch（默认 1000 条/批）
            if (!toInsert.isEmpty()) {
                exchangeRateService.saveBatch(toInsert, 1000);
                inserted = toInsert.size();
            }
            // 批量更新：用 IService.updateBatchById（分 500 条/批）
            if (!toUpdate.isEmpty()) {
                for (List<ExchangeRateSnapshot> batch : partition(toUpdate, 500)) {
                    exchangeRateService.updateBatchById(batch);
                }
                updated = toUpdate.size();
            }
        }

        // 7. 更新批次总数
        importBatchService.updateTotalCount(batchNo, inserted + updated);

        // 8. 序列化失败行明细（直接传给 markCleaned，避免被覆盖）
        String errorDetailJson = null;
        if (!errors.isEmpty()) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
                List<Map<String, Object>> failedRows = new ArrayList<>();
                for (FileParser.ParseResult.RowError err : errors) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("rowNo", err.rowNo());
                    row.put("fieldName", "");
                    row.put("fieldValue", err.rawLine());
                    row.put("reason", err.reason());
                    row.put("suggestion", "请检查该行数据是否符合模板要求，或点击「下载模板」获取标准格式");
                    failedRows.add(row);
                }
                errorDetailJson = objectMapper.writeValueAsString(failedRows);
            } catch (Exception e) {
                log.warn("[ExchangeRateImport] 序列化失败行明细异常：{}", e.getMessage());
            }
        }

        // 9. 标记批次完成（汇率不需要清洗步骤，直接 CLEANED；errorDetail 一并写入）
        importBatchService.markCleaned(batchNo, inserted + updated, errors.size(), errorDetailJson);

        // 10. 刷新内存汇率缓存
        try {
            exchangeRateService.refreshCache();
        } catch (Exception e) {
            log.warn("[ExchangeRateImport] 刷新内存汇率缓存失败：{}", e.getMessage());
        }

        log.info("[ExchangeRateImport] 批次 {} 文件 {} 导入完成：新增 {} 条，更新 {} 条，失败 {} 行",
                batchNo, file.getOriginalFilename(), inserted, updated, errors.size());
        return new ImportResult(batchNo, inserted, updated, errors.size(), errors);
    }

    /** 根据实际表头构建字段映射（targetField → 实际列名） */
    private Map<String, String> buildColumnMapping(List<String> headers) {
        Map<String, String> mapping = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : DEFAULT_ALIASES.entrySet()) {
            String targetField = entry.getKey();
            for (String alias : entry.getValue()) {
                for (String header : headers) {
                    if (header != null && header.trim().equalsIgnoreCase(alias)) {
                        mapping.put(targetField, header.trim());
                        break;
                    }
                }
                if (mapping.containsKey(targetField)) break;
            }
        }
        return mapping;
    }

    /** 单行 Map → ExchangeRateSnapshot 实体（复用统一字段标准化工具） */
    private ExchangeRateSnapshot mapRow(Map<String, String> row) {
        String dateStr = row.get("rateDate");
        String from = row.get("fromCurrency");
        String to = row.get("toCurrency");
        String rateStr = row.get("rate");
        if (dateStr == null || from == null || to == null || rateStr == null) {
            return null;
        }
        // 统一日期解析（支持 yyyy-MM-dd、yyyy/MM/dd、yyyy.MM.dd、yyyyMMdd、单位数月/日 等）
        LocalDate rateDate = ImportFieldNormalizer.parseDate(dateStr.trim());
        if (rateDate == null) {
            throw new IllegalArgumentException("日期格式无法识别：" + dateStr
                    + "（支持格式：yyyy-MM-dd、yyyy/MM/dd、yyyy.MM.dd、yyyyMMdd、2026/7/31 等）");
        }
        // 统一金额解析（支持千分位逗号、货币符号等）
        BigDecimal rate = ImportFieldNormalizer.parseDecimal(rateStr.trim());
        if (rate == null) {
            throw new IllegalArgumentException("汇率数值格式错误：" + rateStr
                    + "（请填写纯数字，可含小数点与千分位逗号，如 7.25 或 7.2500）");
        }
        // 统一币种标准化（支持中英文、全角字符，如"美元"→"USD"）
        String fromCurrency = ImportFieldNormalizer.normalizeCurrency(from.trim());
        if (fromCurrency == null) {
            throw new IllegalArgumentException("无法识别的源币种：" + from
                    + "（请使用标准代码如 USD/CNY/EUR，或中文如 美元/人民币/欧元）");
        }
        String toCurrency = ImportFieldNormalizer.normalizeCurrency(to.trim());
        if (toCurrency == null) {
            throw new IllegalArgumentException("无法识别的目标币种：" + to
                    + "（请使用标准代码如 USD/CNY/EUR，或中文如 美元/人民币/欧元）");
        }
        ExchangeRateSnapshot snap = new ExchangeRateSnapshot();
        snap.setRateDate(rateDate);
        snap.setFromCurrency(fromCurrency);
        snap.setToCurrency(toCurrency);
        snap.setRate(rate);
        String source = row.get("source");
        snap.setSource(source == null || source.isBlank() ? DEFAULT_SOURCE : source.trim());
        return snap;
    }

    /** 构建汇率唯一键：rateDate|from|to */
    private String buildRateKey(LocalDate rateDate, String from, String to) {
        return rateDate + "|" + from + "|" + to;
    }

    /** 将列表按 batchSize 切分 */
    private <T> List<List<T>> partition(List<T> list, int batchSize) {
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i += batchSize) {
            result.add(list.subList(i, Math.min(i + batchSize, list.size())));
        }
        return result;
    }

    /**
     * 导入结果
     *
     * @param batchNo   批次号
     * @param inserted  新增条数
     * @param updated   更新条数（同日同币对已存在则覆盖）
     * @param failed    失败行数
     * @param errors    失败行明细
     */
    public record ImportResult(String batchNo, int inserted, int updated, int failed,
                               List<FileParser.ParseResult.RowError> errors) {}
}
