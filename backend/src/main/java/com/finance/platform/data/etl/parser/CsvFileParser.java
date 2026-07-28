package com.finance.platform.data.etl.parser;

import cn.hutool.core.text.csv.CsvData;
import cn.hutool.core.text.csv.CsvReader;
import cn.hutool.core.text.csv.CsvRow;
import cn.hutool.core.text.csv.CsvUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CSV 文件解析器
 * <p>
 * 基于 Hutool CsvReader，支持动态字段映射、表头行号配置、错误行收集。
 * 自动识别 UTF-8 / GBK / GB2312 编码（Windows 中文 Excel 导出的 CSV 通常为 GBK 编码）。
 */
@Slf4j
@Component
public class CsvFileParser implements FileParser {

    private static final int DEFAULT_SAMPLE_ROWS = 10;

    /**
     * 检测文件编码：UTF-8 BOM → UTF-8；严格 UTF-8 验证通过 → UTF-8；否则 → GBK
     * <p>
     * 关键修复：旧实现用 String.contains("\uFFFD") 判断，但读取固定字节数（4096）时
     * 边界可能切在多字节 UTF-8 字符中间，导致末尾产生替换字符，误判为 GBK。
     * 新实现用 CharsetDecoder 严格验证，并处理截断的尾部字节。
     */
    private Charset detectCharset(MultipartFile file) throws Exception {
        byte[] head = new byte[Math.min(8192, (int) file.getSize())];
        int read;
        try (var in = file.getInputStream()) {
            read = in.read(head);
        }
        if (read <= 0) return StandardCharsets.UTF_8;
        // BOM 判断
        if (read >= 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        // 严格 UTF-8 验证：用 CharsetDecoder 报告错误而非替换
        if (isValidUtf8(head, read)) {
            return StandardCharsets.UTF_8;
        }
        return Charset.forName("GBK");
    }

    /**
     * 严格验证字节数组是否为合法 UTF-8
     * <p>
     * 处理尾部截断：如果末尾是多字节字符的前导字节（但不完整），
     * 截掉尾部不完整部分后验证剩余部分。这避免 4096/8192 字节边界切在字符中间导致误判。
     */
    private boolean isValidUtf8(byte[] data, int length) {
        if (length == 0) return true;
        // 找到最后一个完整 UTF-8 字符的结束位置
        int validLen = length;
        // 最多回退 3 字节（UTF-8 最长 4 字节，回退 3 字节足够找到前导字节）
        for (int i = 1; i <= 3 && validLen > 0; i++) {
            byte b = data[validLen - 1];
            // 前导字节（11xxxxxx）说明这是多字节字符的起始
            if ((b & 0xC0) == 0xC0) {
                // 计算该字符应有的字节数
                int expected = utf8CharLength(b);
                if (expected > 0 && i < expected) {
                    // 尾部字符不完整，截掉
                    validLen -= i;
                }
                break;
            }
            // 连续字节（10xxxxxx）继续回退
            if ((b & 0xC0) != 0x80) {
                // 单字节字符（0xxxxxx），完整
                break;
            }
        }
        if (validLen == 0) return true;
        try {
            java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            decoder.decode(java.nio.ByteBuffer.wrap(data, 0, validLen));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 返回 UTF-8 前导字节对应的字符长度，非前导字节返回 0 */
    private int utf8CharLength(byte b) {
        if ((b & 0x80) == 0) return 1;       // 0xxxxxxx
        if ((b & 0xE0) == 0xC0) return 2;    // 110xxxxx
        if ((b & 0xF0) == 0xE0) return 3;    // 1110xxxx
        if ((b & 0xF8) == 0xF0) return 4;    // 11110xxx
        return 0;
    }

    /**
     * 跳过 UTF-8 BOM（0xEF 0xBB 0xBF），返回包装后的 InputStream
     * <p>
     * 未检测到 BOM 时原样返回（用 PushbackInputStream 回退已读字节）。
     * 关键作用：避免 BOM 字符（\ufeff）混入首列表头导致字段映射失败
     * （如 "\ufeff订单号" 无法匹配字段映射中的 "订单号"）。
     */
    private InputStream skipUtf8Bom(InputStream in) throws Exception {
        PushbackInputStream pin = new PushbackInputStream(in, 3);
        byte[] bom = new byte[3];
        int read = pin.read(bom);
        if (read == 3
                && (bom[0] & 0xFF) == 0xEF
                && (bom[1] & 0xFF) == 0xBB
                && (bom[2] & 0xFF) == 0xBF) {
            return pin; // 已消费 BOM，直接返回
        }
        if (read > 0) {
            pin.unread(bom, 0, read);
        }
        return pin;
    }

    @Override
    public ParseResult parse(MultipartFile file, ParseConfig config) {
        List<Map<String, String>> successRows = new ArrayList<>();
        List<ParseResult.RowError> failedRows = new ArrayList<>();
        Charset charset;
        try {
            charset = detectCharset(file);
        } catch (Exception e) {
            charset = StandardCharsets.UTF_8;
        }
        log.info("[CsvParser] 文件 {} 使用编码 {} 解析", file.getOriginalFilename(), charset.name());
        try (Reader reader = new InputStreamReader(skipUtf8Bom(file.getInputStream()), charset)) {
            CsvReader csvReader = CsvUtil.getReader();
            CsvData csvData = csvReader.read(reader);
            if (csvData.getRowCount() == 0) {
                return new ParseResult(successRows, failedRows);
            }
            int startRow = config.headerRow() - 1;
            List<String> headers = csvData.getRow(startRow).getRawList();
            for (int i = startRow + 1; i < csvData.getRowCount(); i++) {
                CsvRow row = csvData.getRow(i);
                int rowNo = i + 1;
                try {
                    List<String> rawList = row.getRawList();
                    // 跳过完全空白的行（避免 Excel 导出末尾的空行误判为字段缺失）
                    if (rawList.stream().allMatch(s -> s == null || s.trim().isEmpty())) {
                        continue;
                    }
                    Map<String, String> mapped = new HashMap<>();
                    for (int col = 0; col < rawList.size() && col < headers.size(); col++) {
                        String headerName = headers.get(col);
                        if (headerName == null || headerName.trim().isEmpty()) continue;
                        String targetField = matchField(config.columnMapping(), headerName);
                        if (targetField != null) {
                            mapped.put(targetField, rawList.get(col));
                        }
                    }
                    if (mapped.isEmpty()) {
                        failedRows.add(new ParseResult.RowError(rowNo, row.toString(), "无法匹配任何字段"));
                    } else {
                        successRows.add(mapped);
                    }
                } catch (Exception e) {
                    failedRows.add(new ParseResult.RowError(rowNo, row.toString(), e.getMessage()));
                }
            }
            log.info("[CsvParser] 文件 {} 解析完成，成功 {} 行，失败 {} 行",
                    file.getOriginalFilename(), successRows.size(), failedRows.size());
        } catch (Exception e) {
            log.error("[CsvParser] 解析失败 file={}", file.getOriginalFilename(), e);
            throw new RuntimeException("CSV 解析失败：" + e.getMessage(), e);
        }
        return new ParseResult(successRows, failedRows);
    }

    @Override
    public Map<String, Object> readHeadAndSamples(MultipartFile file, int headRows) {
        int sampleRows = headRows <= 0 ? DEFAULT_SAMPLE_ROWS : headRows;
        List<String> headers = new ArrayList<>();
        List<Map<String, String>> samples = new ArrayList<>();
        Charset charset;
        try {
            charset = detectCharset(file);
        } catch (Exception e) {
            charset = StandardCharsets.UTF_8;
        }
        try (Reader reader = new InputStreamReader(skipUtf8Bom(file.getInputStream()), charset)) {
            CsvReader csvReader = CsvUtil.getReader();
            CsvData csvData = csvReader.read(reader);
            if (csvData.getRowCount() > 0) {
                headers.addAll(csvData.getRow(0).getRawList());
                int end = Math.min(csvData.getRowCount(), sampleRows + 1);
                for (int i = 1; i < end; i++) {
                    CsvRow row = csvData.getRow(i);
                    Map<String, String> map = new LinkedHashMap<>();
                    List<String> raw = row.getRawList();
                    for (int col = 0; col < raw.size() && col < headers.size(); col++) {
                        map.put(headers.get(col), raw.get(col));
                    }
                    samples.add(map);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("读取 CSV 表头失败：" + e.getMessage(), e);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("headers", headers);
        result.put("samples", samples);
        return result;
    }

    @Override
    public String supportedType() {
        return "CSV";
    }
}
