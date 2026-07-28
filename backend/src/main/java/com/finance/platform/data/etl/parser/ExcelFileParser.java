package com.finance.platform.data.etl.parser;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.metadata.data.ReadCellData;
import com.alibaba.excel.read.listener.ReadListener;
import com.alibaba.excel.read.metadata.holder.ReadRowHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Excel 文件解析器
 * <p>
 * 基于动态字段映射（不依赖 {@code @ExcelProperty}）：
 * 1. 读取 headerRow 行作为表头
 * 2. 后续行按表头与 columnMapping 映射到目标字段名（驼峰）
 * 3. 解析失败的行收集到 failedRows，不中断整体解析
 * <p>
 * EasyExcel 4.x 移除了 {@code invokeHeadMap}，需改用 {@code invokeHead(Map<Integer, ReadCellData<?>>, AnalysisContext)}，
 * 通过 {@link ReadCellData#getStringValue()} 提取表头字符串。
 */
@Slf4j
@Component
public class ExcelFileParser implements FileParser {

    private static final int DEFAULT_SAMPLE_ROWS = 10;

    @Override
    public ParseResult parse(MultipartFile file, ParseConfig config) {
        List<Map<String, String>> successRows = new ArrayList<>();
        List<ParseResult.RowError> failedRows = new ArrayList<>();
        try (InputStream in = file.getInputStream()) {
            EasyExcel.read(in, null, new ReadListener<Map<Integer, String>>() {
                private List<String> headers = Collections.emptyList();

                @Override
                public void invokeHead(Map<Integer, ReadCellData<?>> headMap, AnalysisContext context) {
                    // 表头：按列索引排序后存为 List
                    int maxIdx = headMap.keySet().stream().max(Integer::compareTo).orElse(-1);
                    headers = new ArrayList<>(Collections.nCopies(maxIdx + 1, ""));
                    headMap.forEach((k, v) -> {
                        if (k != null && k >= 0 && k <= maxIdx && v != null) {
                            headers.set(k, v.getStringValue());
                        }
                    });
                    log.debug("[ExcelParser] 表头：{}", headers);
                }

                @Override
                public void invoke(Map<Integer, String> rowData, AnalysisContext context) {
                    int rowIndex = ((ReadRowHolder) context.readRowHolder()).getRowIndex() + 1;
                    if (rowData == null || rowData.isEmpty()) {
                        return;
                    }
                    // 数据行：按表头 + columnMapping 映射
                    Map<String, String> mapped = new HashMap<>();
                    for (Map.Entry<Integer, String> entry : rowData.entrySet()) {
                        Integer colIdx = entry.getKey();
                        if (colIdx == null || colIdx >= headers.size()) continue;
                        String headerName = headers.get(colIdx);
                        if (headerName == null || headerName.trim().isEmpty()) continue;
                        // 容错匹配（去 BOM/引号/空格，忽略大小写），逻辑在 FileParser 接口 default 方法
                        String targetField = matchField(config.columnMapping(), headerName);
                        if (targetField != null) {
                            mapped.put(targetField, entry.getValue());
                        }
                    }
                    if (mapped.isEmpty()) {
                        failedRows.add(new ParseResult.RowError(rowIndex, rowData.toString(), "无法匹配任何字段"));
                    } else {
                        successRows.add(mapped);
                    }
                }

                @Override
                public void doAfterAllAnalysed(AnalysisContext context) {
                    log.info("[ExcelParser] 文件 {} 解析完成，成功 {} 行，失败 {} 行",
                            file.getOriginalFilename(), successRows.size(), failedRows.size());
                }
            }).sheet(config.sheetNo()).headRowNumber(config.headerRow()).doRead();
        } catch (IOException e) {
            log.error("[ExcelParser] 解析失败 file={}", file.getOriginalFilename(), e);
            throw new RuntimeException("Excel 解析失败：" + e.getMessage(), e);
        }
        return new ParseResult(successRows, failedRows);
    }

    @Override
    public Map<String, Object> readHeadAndSamples(MultipartFile file, int headRows) {
        int sampleRows = headRows <= 0 ? DEFAULT_SAMPLE_ROWS : headRows;
        List<String> headers = new ArrayList<>();
        List<Map<String, String>> samples = new ArrayList<>();
        try (InputStream in = file.getInputStream()) {
            EasyExcel.read(in, null, new ReadListener<Map<Integer, String>>() {
                @Override
                public void invokeHead(Map<Integer, ReadCellData<?>> headMap, AnalysisContext context) {
                    headMap.entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .forEach(e -> headers.add(e.getValue() == null ? "" : e.getValue().getStringValue()));
                }

                @Override
                public void invoke(Map<Integer, String> data, AnalysisContext context) {
                    if (samples.size() >= sampleRows) return;
                    Map<String, String> row = new LinkedHashMap<>();
                    data.forEach((k, v) -> {
                        if (k != null && k < headers.size()) {
                            row.put(headers.get(k), v);
                        }
                    });
                    samples.add(row);
                }

                @Override
                public void doAfterAllAnalysed(AnalysisContext context) {
                    // no-op
                }
            }).sheet().headRowNumber(1).doRead();
        } catch (IOException e) {
            throw new RuntimeException("读取 Excel 表头失败：" + e.getMessage(), e);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("headers", headers);
        result.put("samples", samples);
        return result;
    }

    @Override
    public String supportedType() {
        return "EXCEL";
    }
}
