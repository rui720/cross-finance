package com.finance.platform.data.etl.parser;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 文件解析器抽象接口
 * <p>
 * 解耦「文件格式」与「字段映射」：不同格式（Excel/CSV）各自实现，
 * 字段映射通过 {@link ParseConfig} 传入，不再依赖 {@code @ExcelProperty} 注解硬编码。
 * <p>
 * 设计目标：
 * 1. 支持任意列名（中文/英文/缩写）通过配置映射到目标字段
 * 2. 支持多 sheet 选择、表头行号配置
 * 3. 解析失败时收集行级错误明细，不整体中断
 */
public interface FileParser {

    /**
     * 解析文件，返回解析结果（含成功数据 + 失败行明细）
     *
     * @param file   上传的文件
     * @param config 解析配置（字段映射、表头行号、sheet 索引等）
     * @return 解析结果
     */
    ParseResult parse(MultipartFile file, ParseConfig config);

    /**
     * 仅读取文件表头（前若干行），用于 AI 自动识别字段映射
     *
     * @param file     文件
     * @param headRows 读取的表头 + 样例行数（默认 10）
     * @return 表头与样例数据，key="headers"(List&lt;String&gt;) / "samples"(List&lt;Map&lt;String,String&gt;&gt;)
     */
    Map<String, Object> readHeadAndSamples(MultipartFile file, int headRows);

    /**
     * 该解析器支持的文件类型标识（EXCEL / CSV）
     */
    String supportedType();

    /**
     * 解析配置：字段映射 + 表头行号 + sheet 索引
     */
    record ParseConfig(
            Map<String, String> columnMapping,
            int headerRow,
            int sheetNo
    ) {
        public ParseConfig {
            if (headerRow <= 0) headerRow = 1;
            if (sheetNo < 0) sheetNo = 0;
        }
    }

    /**
     * 解析结果：成功数据 + 失败行明细
     */
    record ParseResult(
            List<Map<String, String>> successRows,
            List<RowError> failedRows
    ) {
        public record RowError(int rowNo, String rawLine, String reason) {}
    }

    /**
     * 容错字段匹配：将实际表头与字段映射中的目标列名进行匹配
     * <p>
     * 容错策略（逐步降级）：
     * <ol>
     *   <li>两端 trim + 去除 BOM 残留（\ufeff）</li>
     *   <li>去除两端引号（"订单号" / '订单号'）</li>
     *   <li>精确匹配（清洗后相等）</li>
     *   <li>忽略大小写匹配（对英文表头有用，如 orderNo vs orderno）</li>
     * </ol>
     * <p>
     * 两个解析器（CSV/Excel）共用此逻辑，确保表头匹配容错策略一致。
     *
     * @param columnMapping 字段映射：{targetField: 实际列名}
     * @param headerName   实际表头（可能含 BOM、引号、空格）
     * @return 匹配到的 targetField，未匹配返回 null
     */
    default String matchField(Map<String, String> columnMapping, String headerName) {
        if (headerName == null) return null;
        String cleanedHeader = normalizeHeader(headerName);
        if (cleanedHeader.isEmpty()) return null;
        // 精确匹配
        for (Map.Entry<String, String> e : columnMapping.entrySet()) {
            String expected = normalizeHeader(e.getValue());
            if (cleanedHeader.equals(expected)) {
                return e.getKey();
            }
        }
        // 忽略大小写匹配
        String lowerHeader = cleanedHeader.toLowerCase();
        for (Map.Entry<String, String> e : columnMapping.entrySet()) {
            String expected = normalizeHeader(e.getValue());
            if (lowerHeader.equals(expected.toLowerCase())) {
                return e.getKey();
            }
        }
        return null;
    }

    /** 清洗表头：去 BOM 残留、trim、去两端引号 */
    default String normalizeHeader(String s) {
        if (s == null) return "";
        String r = s;
        if (r.startsWith("\ufeff")) {
            r = r.substring(1);
        }
        r = r.trim();
        if (r.length() >= 2
                && ((r.charAt(0) == '"' && r.charAt(r.length() - 1) == '"')
                || (r.charAt(0) == '\'' && r.charAt(r.length() - 1) == '\''))) {
            r = r.substring(1, r.length() - 1).trim();
        }
        return r;
    }
}
