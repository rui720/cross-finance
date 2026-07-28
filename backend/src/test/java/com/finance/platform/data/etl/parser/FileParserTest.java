package com.finance.platform.data.etl.parser;

import com.alibaba.excel.EasyExcel;
import com.finance.platform.common.utils.ImportFieldNormalizer;
import com.finance.platform.data.entity.RawOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件解析器单元测试（方案 B 核心）
 * <p>
 * 覆盖：
 * 1. CsvFileParser：动态字段映射、表头行配置、失败行收集、readHeadAndSamples
 * 2. ExcelFileParser：动态字段映射（不依赖 @ExcelProperty）、readHeadAndSamples
 * 3. RawOrderRowMapper：类型转换（BigDecimal/LocalDateTime 多格式）
 * 4. FileParserFactory：按文件类型/扩展名选择解析器
 */
@DisplayName("文件解析器测试")
class FileParserTest {

    // ==================== CsvFileParser ====================

    @Test
    @DisplayName("CsvFileParser：动态字段映射解析成功")
    void csvParserParsesWithColumnMapping() {
        String csv = "订单号,平台,币种,金额\n"
                + "ORD-001,Amazon,USD,1500.00\n"
                + "ORD-002,Amazon,USD,2200.00\n";
        MultipartFile file = new MockMultipartFile("file", "test.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        Map<String, String> mapping = Map.of(
                "orderNo", "订单号",
                "platform", "平台",
                "currency", "币种",
                "amount", "金额");
        FileParser.ParseConfig config = new FileParser.ParseConfig(mapping, 1, 0);

        FileParser.ParseResult result = new CsvFileParser().parse(file, config);

        assertThat(result.successRows()).hasSize(2);
        assertThat(result.failedRows()).isEmpty();
        Map<String, String> row1 = result.successRows().get(0);
        assertThat(row1.get("orderNo")).isEqualTo("ORD-001");
        assertThat(row1.get("platform")).isEqualTo("Amazon");
        assertThat(row1.get("currency")).isEqualTo("USD");
        assertThat(row1.get("amount")).isEqualTo("1500.00");
    }

    @Test
    @DisplayName("CsvFileParser：readHeadAndSamples 返回表头和样例")
    void csvParserReadHeadAndSamples() {
        String csv = "订单号,平台,币种\nORD-001,Amazon,USD\nORD-002,Amazon,USD\n";
        MultipartFile file = new MockMultipartFile("file", "test.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        Map<String, Object> result = new CsvFileParser().readHeadAndSamples(file, 5);

        @SuppressWarnings("unchecked")
        List<String> headers = (List<String>) result.get("headers");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> samples = (List<Map<String, String>>) result.get("samples");

        assertThat(headers).containsExactly("订单号", "平台", "币种");
        assertThat(samples).hasSize(2);
        assertThat(samples.get(0)).containsEntry("订单号", "ORD-001").containsEntry("平台", "Amazon");
    }

    @Test
    @DisplayName("CsvFileParser：supportedType 返回 CSV")
    void csvParserSupportedType() {
        assertThat(new CsvFileParser().supportedType()).isEqualTo("CSV");
    }

    @Test
    @DisplayName("CsvFileParser：未匹配字段的行进入 failedRows")
    void csvParserCollectsUnmatchedRows() {
        // 列名完全无法匹配，整行应进入 failedRows
        String csv = "colA,colB\nv1,v2\n";
        MultipartFile file = new MockMultipartFile("file", "test.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
        Map<String, String> mapping = Map.of("orderNo", "订单号");
        FileParser.ParseConfig config = new FileParser.ParseConfig(mapping, 1, 0);

        FileParser.ParseResult result = new CsvFileParser().parse(file, config);

        assertThat(result.successRows()).isEmpty();
        assertThat(result.failedRows()).hasSize(1);
        assertThat(result.failedRows().get(0).reason()).contains("无法匹配");
    }

    // ==================== ExcelFileParser ====================

    @Test
    @DisplayName("ExcelFileParser：动态字段映射解析成功")
    void excelParserParsesWithColumnMapping() throws Exception {
        byte[] xlsx = writeTestExcel(List.of(
                List.of("订单号", "平台", "币种", "金额"),
                List.of("ORD-001", "Amazon", "USD", "1500.00"),
                List.of("ORD-002", "Amazon", "USD", "2200.00")));
        MultipartFile file = new MockMultipartFile("file", "test.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx);

        Map<String, String> mapping = Map.of(
                "orderNo", "订单号",
                "platform", "平台",
                "currency", "币种",
                "amount", "金额");
        FileParser.ParseConfig config = new FileParser.ParseConfig(mapping, 1, 0);

        FileParser.ParseResult result = new ExcelFileParser().parse(file, config);

        assertThat(result.successRows()).hasSize(2);
        assertThat(result.failedRows()).isEmpty();
        Map<String, String> row1 = result.successRows().get(0);
        assertThat(row1.get("orderNo")).isEqualTo("ORD-001");
        assertThat(row1.get("platform")).isEqualTo("Amazon");
        assertThat(row1.get("currency")).isEqualTo("USD");
        assertThat(row1.get("amount")).isEqualTo("1500.00");
    }

    @Test
    @DisplayName("ExcelFileParser：readHeadAndSamples 返回表头和样例")
    void excelParserReadHeadAndSamples() throws Exception {
        byte[] xlsx = writeTestExcel(List.of(
                List.of("订单号", "平台", "币种"),
                List.of("ORD-001", "Amazon", "USD"),
                List.of("ORD-002", "Amazon", "USD")));
        MultipartFile file = new MockMultipartFile("file", "test.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx);

        Map<String, Object> result = new ExcelFileParser().readHeadAndSamples(file, 5);

        @SuppressWarnings("unchecked")
        List<String> headers = (List<String>) result.get("headers");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> samples = (List<Map<String, String>>) result.get("samples");

        assertThat(headers).containsExactly("订单号", "平台", "币种");
        assertThat(samples).hasSize(2);
        assertThat(samples.get(0)).containsEntry("订单号", "ORD-001");
    }

    @Test
    @DisplayName("ExcelFileParser：supportedType 返回 EXCEL")
    void excelParserSupportedType() {
        assertThat(new ExcelFileParser().supportedType()).isEqualTo("EXCEL");
    }

    /** 使用 EasyExcel 写入测试用 Excel 文件到内存 */
    private static byte[] writeTestExcel(List<List<String>> rows) {
        // 第一行为表头，其余为数据
        List<String> header = rows.get(0);
        List<List<String>> data = rows.subList(1, rows.size());
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        EasyExcel.write(bos)
                .head(header.stream().map(List::of).toList())
                .sheet("Sheet1")
                .doWrite(data.stream().map(row -> (List<Object>) new java.util.ArrayList<Object>(row)).toList());
        return bos.toByteArray();
    }

    // ==================== RawOrderRowMapper ====================

    @Test
    @DisplayName("RawOrderRowMapper：完整字段映射")
    void rowMapperFullMapping() {
        Map<String, String> row = Map.of(
                "orderNo", "ORD-001",
                "platform", "Amazon",
                "shopId", "SHOP001",
                "currency", "USD",
                "amount", "1500.00",
                "fee", "150.00",
                "settleAmount", "10875.00",
                "orderTime", "2026-07-01 10:00:00",
                "settleTime", "2026-07-03 10:00:00");
        RawOrder o = new RawOrderRowMapper().map(row);
        assertThat(o.getOrderNo()).isEqualTo("ORD-001");
        assertThat(o.getPlatform()).isEqualTo("Amazon");
        assertThat(o.getShopId()).isEqualTo("SHOP001");
        assertThat(o.getCurrency()).isEqualTo("USD");
        assertThat(o.getAmount()).isEqualByComparingTo("1500.00");
        assertThat(o.getFee()).isEqualByComparingTo("150.00");
        assertThat(o.getSettleAmount()).isEqualByComparingTo("10875.00");
        assertThat(o.getOrderTime()).isEqualTo(LocalDateTime.of(2026, 7, 1, 10, 0, 0));
        assertThat(o.getSettleTime()).isEqualTo(LocalDateTime.of(2026, 7, 3, 10, 0, 0));
    }

    @Test
    @DisplayName("RawOrderRowMapper：金额带千分位逗号")
    void rowMapperAmountWithThousandsSeparator() {
        Map<String, String> row = Map.of("orderNo", "ORD-001", "amount", "1,500.00");
        RawOrder o = new RawOrderRowMapper().map(row);
        assertThat(o.getAmount()).isEqualByComparingTo("1500.00");
    }

    @Test
    @DisplayName("RawOrderRowMapper：多日期格式解析")
    void rowMapperMultipleDateFormats() {
        RawOrderRowMapper mapper = new RawOrderRowMapper();
        // yyyy-MM-dd HH:mm:ss
        Map<String, String> r1 = Map.of("orderNo", "X", "orderTime", "2026-07-01 10:00:00");
        assertThat(mapper.map(r1).getOrderTime()).isEqualTo(LocalDateTime.of(2026, 7, 1, 10, 0, 0));
        // yyyy/MM/dd HH:mm
        Map<String, String> r2 = Map.of("orderNo", "X", "orderTime", "2026/07/01 10:00");
        assertThat(mapper.map(r2).getOrderTime()).isEqualTo(LocalDateTime.of(2026, 7, 1, 10, 0));
        // yyyy-MM-dd（仅日期）
        Map<String, String> r3 = Map.of("orderNo", "X", "orderTime", "2026-07-01");
        assertThat(mapper.map(r3).getOrderTime()).isEqualTo(LocalDateTime.of(2026, 7, 1, 0, 0));
        // yyyy/MM/dd
        Map<String, String> r4 = Map.of("orderNo", "X", "orderTime", "2026/07/01");
        assertThat(mapper.map(r4).getOrderTime()).isEqualTo(LocalDateTime.of(2026, 7, 1, 0, 0));
    }

    @Test
    @DisplayName("RawOrderRowMapper：单位数月/日时间格式（Excel/CSV 常见，如 2026/7/31 10:00）")
    void rowMapperSingleDigitMonthDayFormats() {
        RawOrderRowMapper mapper = new RawOrderRowMapper();
        // yyyy/M/d H:m（单位数月日时分，CSV 常见）
        Map<String, String> r1 = Map.of("orderNo", "X", "orderTime", "2026/7/31 10:00");
        assertThat(mapper.map(r1).getOrderTime()).isEqualTo(LocalDateTime.of(2026, 7, 31, 10, 0));
        // yyyy/M/d H:m:s
        Map<String, String> r2 = Map.of("orderNo", "X", "orderTime", "2026/8/1 14:30:00");
        assertThat(mapper.map(r2).getOrderTime()).isEqualTo(LocalDateTime.of(2026, 8, 1, 14, 30, 0));
        // yyyy-M-d H:m
        Map<String, String> r3 = Map.of("orderNo", "X", "orderTime", "2026-7-31 10:00");
        assertThat(mapper.map(r3).getOrderTime()).isEqualTo(LocalDateTime.of(2026, 7, 31, 10, 0));
        // yyyy/M/d（仅日期，单位数）
        Map<String, String> r4 = Map.of("orderNo", "X", "orderTime", "2026/7/31");
        assertThat(mapper.map(r4).getOrderTime()).isEqualTo(LocalDateTime.of(2026, 7, 31, 0, 0));
    }

    @Test
    @DisplayName("RawOrderRowMapper：金额解析失败返回 null")
    void rowMapperInvalidAmountReturnsNull() {
        Map<String, String> row = Map.of("orderNo", "X", "amount", "abc");
        RawOrder o = new RawOrderRowMapper().map(row);
        assertThat(o.getAmount()).isNull();
    }

    @Test
    @DisplayName("RawOrderRowMapper：空字段返回 null")
    void rowMapperEmptyFieldsReturnNull() {
        Map<String, String> row = Map.of("orderNo", "X", "amount", "  ");
        RawOrder o = new RawOrderRowMapper().map(row);
        assertThat(o.getAmount()).isNull();
    }

    // ==================== FileParserFactory ====================

    @Test
    @DisplayName("FileParserFactory：按类型获取 EXCEL 解析器")
    void factoryGetExcelParser() {
        FileParserFactory factory = new FileParserFactory(List.of(new ExcelFileParser(), new CsvFileParser()));
        factory.init();
        FileParser parser = factory.getParser("EXCEL");
        assertThat(parser).isInstanceOf(ExcelFileParser.class);
    }

    @Test
    @DisplayName("FileParserFactory：按类型获取 CSV 解析器（大小写不敏感）")
    void factoryGetCsvParserCaseInsensitive() {
        FileParserFactory factory = new FileParserFactory(List.of(new ExcelFileParser(), new CsvFileParser()));
        factory.init();
        FileParser parser = factory.getParser("csv");
        assertThat(parser).isInstanceOf(CsvFileParser.class);
    }

    @Test
    @DisplayName("FileParserFactory：未知类型抛异常")
    void factoryUnknownTypeThrows() {
        FileParserFactory factory = new FileParserFactory(List.of(new ExcelFileParser()));
        factory.init();
        assertThatThrownBy(() -> factory.getParser("XML"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的文件类型");
    }

    @Test
    @DisplayName("FileParserFactory：按文件名 .csv 识别 CSV 解析器")
    void factoryByFileNameCsv() {
        FileParserFactory factory = new FileParserFactory(List.of(new ExcelFileParser(), new CsvFileParser()));
        factory.init();
        assertThat(factory.getParserByFileName("bank_flow.csv")).isInstanceOf(CsvFileParser.class);
    }

    @Test
    @DisplayName("FileParserFactory：按文件名 .xlsx 识别 EXCEL 解析器")
    void factoryByFileNameExcel() {
        FileParserFactory factory = new FileParserFactory(List.of(new ExcelFileParser(), new CsvFileParser()));
        factory.init();
        assertThat(factory.getParserByFileName("amazon_july.xlsx")).isInstanceOf(ExcelFileParser.class);
    }

    @Test
    @DisplayName("FileParserFactory：文件名为空默认 EXCEL 解析器")
    void factoryByFileNameNullDefaultsExcel() {
        FileParserFactory factory = new FileParserFactory(List.of(new ExcelFileParser(), new CsvFileParser()));
        factory.init();
        assertThat(factory.getParserByFileName(null)).isInstanceOf(ExcelFileParser.class);
    }

    // ==================== ImportFieldNormalizer ====================

    @Test
    @DisplayName("ImportFieldNormalizer：币种标准化 - 英文代码")
    void normalizerCurrencyEnglishCode() {
        assertThat(ImportFieldNormalizer.normalizeCurrency("USD")).isEqualTo("USD");
        assertThat(ImportFieldNormalizer.normalizeCurrency("usd")).isEqualTo("USD");
        assertThat(ImportFieldNormalizer.normalizeCurrency("CNY")).isEqualTo("CNY");
        assertThat(ImportFieldNormalizer.normalizeCurrency("EUR")).isEqualTo("EUR");
    }

    @Test
    @DisplayName("ImportFieldNormalizer：币种标准化 - 中文名称")
    void normalizerCurrencyChinese() {
        assertThat(ImportFieldNormalizer.normalizeCurrency("人民币")).isEqualTo("CNY");
        assertThat(ImportFieldNormalizer.normalizeCurrency("美元")).isEqualTo("USD");
        assertThat(ImportFieldNormalizer.normalizeCurrency("欧元")).isEqualTo("EUR");
        assertThat(ImportFieldNormalizer.normalizeCurrency("港币")).isEqualTo("HKD");
        assertThat(ImportFieldNormalizer.normalizeCurrency("日元")).isEqualTo("JPY");
        assertThat(ImportFieldNormalizer.normalizeCurrency("英镑")).isEqualTo("GBP");
    }

    @Test
    @DisplayName("ImportFieldNormalizer：币种标准化 - 别名与全角字符")
    void normalizerCurrencyAliasAndFullWidth() {
        assertThat(ImportFieldNormalizer.normalizeCurrency("RMB")).isEqualTo("CNY");
        assertThat(ImportFieldNormalizer.normalizeCurrency("ＵＳＤ")).isEqualTo("USD");
        assertThat(ImportFieldNormalizer.normalizeCurrency("美元 ")).isEqualTo("USD");
        assertThat(ImportFieldNormalizer.normalizeCurrency("（USD）")).isEqualTo("USD");
        assertThat(ImportFieldNormalizer.normalizeCurrency("港元")).isEqualTo("HKD");
    }

    @Test
    @DisplayName("ImportFieldNormalizer：币种标准化 - 无法识别返回 null")
    void normalizerCurrencyUnrecognized() {
        assertThat(ImportFieldNormalizer.normalizeCurrency("XYZ")).isNull();
        assertThat(ImportFieldNormalizer.normalizeCurrency("火星币")).isNull();
        assertThat(ImportFieldNormalizer.normalizeCurrency(null)).isNull();
        assertThat(ImportFieldNormalizer.normalizeCurrency("")).isNull();
    }

    @Test
    @DisplayName("ImportFieldNormalizer：金额解析 - 千分位逗号")
    void normalizerDecimalThousandsSeparator() {
        assertThat(ImportFieldNormalizer.parseDecimal("1,500.00")).isEqualByComparingTo("1500.00");
        assertThat(ImportFieldNormalizer.parseDecimal("1,234,567.89")).isEqualByComparingTo("1234567.89");
    }

    @Test
    @DisplayName("ImportFieldNormalizer：金额解析 - 货币符号与中文单位")
    void normalizerDecimalCurrencySymbol() {
        assertThat(ImportFieldNormalizer.parseDecimal("¥1500.00")).isEqualByComparingTo("1500.00");
        assertThat(ImportFieldNormalizer.parseDecimal("$100.50")).isEqualByComparingTo("100.50");
        assertThat(ImportFieldNormalizer.parseDecimal("€200")).isEqualByComparingTo("200");
        assertThat(ImportFieldNormalizer.parseDecimal("1500元")).isEqualByComparingTo("1500");
        assertThat(ImportFieldNormalizer.parseDecimal("100.5圆")).isEqualByComparingTo("100.5");
    }

    @Test
    @DisplayName("ImportFieldNormalizer：金额解析 - 括号负数（会计格式）")
    void normalizerDecimalParenthesesNegative() {
        assertThat(ImportFieldNormalizer.parseDecimal("(1500.00)")).isEqualByComparingTo("-1500.00");
        assertThat(ImportFieldNormalizer.parseDecimal("(100)")).isEqualByComparingTo("-100");
    }

    @Test
    @DisplayName("ImportFieldNormalizer：金额解析 - 全角字符")
    void normalizerDecimalFullWidth() {
        assertThat(ImportFieldNormalizer.parseDecimal("１５００")).isEqualByComparingTo("1500");
        assertThat(ImportFieldNormalizer.parseDecimal("￥100")).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("ImportFieldNormalizer：金额解析 - 无效值返回 null")
    void normalizerDecimalInvalid() {
        assertThat(ImportFieldNormalizer.parseDecimal("abc")).isNull();
        assertThat(ImportFieldNormalizer.parseDecimal(null)).isNull();
        assertThat(ImportFieldNormalizer.parseDecimal("")).isNull();
        assertThat(ImportFieldNormalizer.parseDecimal("  ")).isNull();
    }

    @Test
    @DisplayName("ImportFieldNormalizer：日期解析 - 标准格式")
    void normalizerDateStandardFormats() {
        assertThat(ImportFieldNormalizer.parseDate("2026-07-01")).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(ImportFieldNormalizer.parseDate("2026/07/01")).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(ImportFieldNormalizer.parseDate("2026.07.01")).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(ImportFieldNormalizer.parseDate("20260701")).isEqualTo(LocalDate.of(2026, 7, 1));
    }

    @Test
    @DisplayName("ImportFieldNormalizer：日期解析 - 单位数月/日（如 2026/7/1）")
    void normalizerDateSingleDigit() {
        assertThat(ImportFieldNormalizer.parseDate("2026/7/1")).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(ImportFieldNormalizer.parseDate("2026-7-31")).isEqualTo(LocalDate.of(2026, 7, 31));
        assertThat(ImportFieldNormalizer.parseDate("2026.8.15")).isEqualTo(LocalDate.of(2026, 8, 15));
    }

    @Test
    @DisplayName("ImportFieldNormalizer：日期解析 - 含时间部分自动截取日期")
    void normalizerDateWithTimePart() {
        assertThat(ImportFieldNormalizer.parseDate("2026-07-01 10:00:00")).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(ImportFieldNormalizer.parseDate("2026-07-01T10:00:00")).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(ImportFieldNormalizer.parseDate("2026-07-01 10:00:00.123")).isEqualTo(LocalDate.of(2026, 7, 1));
    }

    @Test
    @DisplayName("ImportFieldNormalizer：日期解析 - 无效值返回 null")
    void normalizerDateInvalid() {
        assertThat(ImportFieldNormalizer.parseDate("abc")).isNull();
        assertThat(ImportFieldNormalizer.parseDate(null)).isNull();
        assertThat(ImportFieldNormalizer.parseDate("")).isNull();
    }

    @Test
    @DisplayName("ImportFieldNormalizer：日期时间解析 - 多种格式")
    void normalizerDateTimeMultipleFormats() {
        assertThat(ImportFieldNormalizer.parseDateTime("2026-07-01 10:00:00"))
                .isEqualTo(LocalDateTime.of(2026, 7, 1, 10, 0, 0));
        assertThat(ImportFieldNormalizer.parseDateTime("2026/07/01 10:00"))
                .isEqualTo(LocalDateTime.of(2026, 7, 1, 10, 0));
        assertThat(ImportFieldNormalizer.parseDateTime("2026-07-01"))
                .isEqualTo(LocalDateTime.of(2026, 7, 1, 0, 0));
        assertThat(ImportFieldNormalizer.parseDateTime("2026/7/31 10:00"))
                .isEqualTo(LocalDateTime.of(2026, 7, 31, 10, 0));
    }

    @Test
    @DisplayName("ImportFieldNormalizer：全角转半角")
    void normalizerHalfWidth() {
        assertThat(ImportFieldNormalizer.halfWidth("ＵＳＤ")).isEqualTo("USD");
        assertThat(ImportFieldNormalizer.halfWidth("１２３")).isEqualTo("123");
        assertThat(ImportFieldNormalizer.halfWidth("ＡＢＣ")).isEqualTo("ABC");
        assertThat(ImportFieldNormalizer.halfWidth(null)).isNull();
    }
}
