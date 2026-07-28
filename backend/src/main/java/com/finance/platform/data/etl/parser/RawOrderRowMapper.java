package com.finance.platform.data.etl.parser;

import com.finance.platform.data.etl.FieldMappingException;
import com.finance.platform.data.entity.RawOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.SignStyle;
import java.time.temporal.ChronoField;
import java.util.Locale;
import java.util.Map;

/**
 * 行数据 → RawOrder 实体映射器
 * <p>
 * 将解析器返回的 Map&lt;String,String&gt;（目标字段名 → 字符串值）
 * 按字段类型转换为 RawOrder 实体属性。
 * <p>
 * 提供两种映射方式：
 * <ul>
 *   <li>{@link #map}：宽容模式，解析失败返回 null（向后兼容，用于测试与默认场景）</li>
 *   <li>{@link #mapStrict}：严格模式，必填字段为空 / 格式错误时抛出 {@link FieldMappingException}，
 *       携带字段名 / 原值 / 失败原因 / 修复建议，供导入服务按行收集错误明细</li>
 * </ul>
 */
@Slf4j
@Component
public class RawOrderRowMapper {

    /** 含时间部分的格式（按 LocalDateTime 解析） */
    private static final DateTimeFormatter[] DATETIME_FORMATS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm"),
            // 宽松格式：支持单位数月/日（如 2026/7/31 10:00），Excel/CSV 常见格式
            new DateTimeFormatterBuilder()
                    .appendValue(ChronoField.YEAR, 4)
                    .appendLiteral('/')
                    .appendValue(ChronoField.MONTH_OF_YEAR, 1, 2, SignStyle.NORMAL)
                    .appendLiteral('/')
                    .appendValue(ChronoField.DAY_OF_MONTH, 1, 2, SignStyle.NORMAL)
                    .appendLiteral(' ')
                    .appendValue(ChronoField.HOUR_OF_DAY, 1, 2, SignStyle.NORMAL)
                    .appendLiteral(':')
                    .appendValue(ChronoField.MINUTE_OF_HOUR, 1, 2, SignStyle.NORMAL)
                    .toFormatter(),
            new DateTimeFormatterBuilder()
                    .appendValue(ChronoField.YEAR, 4)
                    .appendLiteral('/')
                    .appendValue(ChronoField.MONTH_OF_YEAR, 1, 2, SignStyle.NORMAL)
                    .appendLiteral('/')
                    .appendValue(ChronoField.DAY_OF_MONTH, 1, 2, SignStyle.NORMAL)
                    .appendLiteral(' ')
                    .appendValue(ChronoField.HOUR_OF_DAY, 1, 2, SignStyle.NORMAL)
                    .appendLiteral(':')
                    .appendValue(ChronoField.MINUTE_OF_HOUR, 1, 2, SignStyle.NORMAL)
                    .appendLiteral(':')
                    .appendValue(ChronoField.SECOND_OF_MINUTE, 1, 2, SignStyle.NORMAL)
                    .toFormatter(),
            new DateTimeFormatterBuilder()
                    .appendValue(ChronoField.YEAR, 4)
                    .appendLiteral('-')
                    .appendValue(ChronoField.MONTH_OF_YEAR, 1, 2, SignStyle.NORMAL)
                    .appendLiteral('-')
                    .appendValue(ChronoField.DAY_OF_MONTH, 1, 2, SignStyle.NORMAL)
                    .appendLiteral(' ')
                    .appendValue(ChronoField.HOUR_OF_DAY, 1, 2, SignStyle.NORMAL)
                    .appendLiteral(':')
                    .appendValue(ChronoField.MINUTE_OF_HOUR, 1, 2, SignStyle.NORMAL)
                    .toFormatter(),
            new DateTimeFormatterBuilder()
                    .appendValue(ChronoField.YEAR, 4)
                    .appendLiteral('-')
                    .appendValue(ChronoField.MONTH_OF_YEAR, 1, 2, SignStyle.NORMAL)
                    .appendLiteral('-')
                    .appendValue(ChronoField.DAY_OF_MONTH, 1, 2, SignStyle.NORMAL)
                    .appendLiteral(' ')
                    .appendValue(ChronoField.HOUR_OF_DAY, 1, 2, SignStyle.NORMAL)
                    .appendLiteral(':')
                    .appendValue(ChronoField.MINUTE_OF_HOUR, 1, 2, SignStyle.NORMAL)
                    .appendLiteral(':')
                    .appendValue(ChronoField.SECOND_OF_MINUTE, 1, 2, SignStyle.NORMAL)
                    .toFormatter(),
            new DateTimeFormatterBuilder()
                    .appendValue(ChronoField.YEAR, 4)
                    .appendLiteral('.')
                    .appendValue(ChronoField.MONTH_OF_YEAR, 1, 2, SignStyle.NORMAL)
                    .appendLiteral('.')
                    .appendValue(ChronoField.DAY_OF_MONTH, 1, 2, SignStyle.NORMAL)
                    .appendLiteral(' ')
                    .appendValue(ChronoField.HOUR_OF_DAY, 1, 2, SignStyle.NORMAL)
                    .appendLiteral(':')
                    .appendValue(ChronoField.MINUTE_OF_HOUR, 1, 2, SignStyle.NORMAL)
                    .toFormatter()
    };

    /** 仅日期格式（解析后补 00:00:00） */
    private static final DateTimeFormatter[] DATE_ONLY_FORMATS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("yyyy.MM.dd"),
            // yyyyMMdd：使用 builder 强制 year=4 位，避免 ofPattern("yyyy") 贪婪解析 8 位
            new DateTimeFormatterBuilder()
                    .appendValue(ChronoField.YEAR, 4)
                    .appendValue(ChronoField.MONTH_OF_YEAR, 2)
                    .appendValue(ChronoField.DAY_OF_MONTH, 2)
                    .toFormatter(),
            // 宽松格式：支持单位数月/日（如 2026/7/31）
            new DateTimeFormatterBuilder()
                    .appendValue(ChronoField.YEAR, 4)
                    .appendLiteral('/')
                    .appendValue(ChronoField.MONTH_OF_YEAR, 1, 2, SignStyle.NORMAL)
                    .appendLiteral('/')
                    .appendValue(ChronoField.DAY_OF_MONTH, 1, 2, SignStyle.NORMAL)
                    .toFormatter(),
            new DateTimeFormatterBuilder()
                    .appendValue(ChronoField.YEAR, 4)
                    .appendLiteral('-')
                    .appendValue(ChronoField.MONTH_OF_YEAR, 1, 2, SignStyle.NORMAL)
                    .appendLiteral('-')
                    .appendValue(ChronoField.DAY_OF_MONTH, 1, 2, SignStyle.NORMAL)
                    .toFormatter(),
            new DateTimeFormatterBuilder()
                    .appendValue(ChronoField.YEAR, 4)
                    .appendLiteral('.')
                    .appendValue(ChronoField.MONTH_OF_YEAR, 1, 2, SignStyle.NORMAL)
                    .appendLiteral('.')
                    .appendValue(ChronoField.DAY_OF_MONTH, 1, 2, SignStyle.NORMAL)
                    .toFormatter()
    };

    /** 支持的币种（大小写不敏感） */
    private static final java.util.Set<String> SUPPORTED_CURRENCIES = java.util.Set.of(
            "CNY", "USD", "EUR", "HKD", "JPY", "GBP", "AUD", "CAD", "SGD", "KRW", "RUB", "BRL", "INR");

    /** 字段中文名映射（用于错误提示） */
    private static final String NAME_ORDER_NO = "订单号";
    private static final String NAME_PLATFORM = "平台";
    private static final String NAME_SHOP_ID = "店铺ID";
    private static final String NAME_CURRENCY = "币种";
    private static final String NAME_AMOUNT = "金额";
    private static final String NAME_FEE = "手续费";
    private static final String NAME_SETTLE_AMOUNT = "结算金额";
    private static final String NAME_ORDER_TIME = "订单时间";
    private static final String NAME_SETTLE_TIME = "结算时间";

    /**
     * 将一行 Map 数据映射为 RawOrder 实体（宽容模式）。
     * <p>
     * 解析失败的字段返回 null，不抛异常。用于向后兼容场景与单元测试。
     * <p>
     * 注意：实际导入流程应使用 {@link #mapStrict}，以便收集字段级错误明细。
     */
    public RawOrder map(Map<String, String> row) {
        RawOrder order = new RawOrder();
        order.setOrderNo(getStr(row, "orderNo"));
        order.setPlatform(getStr(row, "platform"));
        order.setShopId(getStr(row, "shopId"));
        order.setCurrency(getStr(row, "currency"));
        order.setAmount(getDecimal(row, "amount"));
        order.setFee(getDecimal(row, "fee"));
        order.setSettleAmount(getDecimal(row, "settleAmount"));
        order.setOrderTime(getDateTime(row, "orderTime"));
        order.setSettleTime(getDateTime(row, "settleTime"));
        return order;
    }

    /**
     * 严格模式映射：必填字段为空 / 格式错误时抛出 {@link FieldMappingException}。
     * <p>
     * 必填字段：订单号 / 金额 / 订单时间 / 币种。
     * 可选字段：平台 / 店铺ID / 手续费 / 结算金额 / 结算时间（仍尝试解析，失败抛异常但 suggestion 不同）。
     *
     * @param row 行数据
     * @return 映射后的 RawOrder
     * @throws FieldMappingException 字段级错误，携带字段名 / 原值 / 修复建议
     */
    public RawOrder mapStrict(Map<String, String> row) throws FieldMappingException {
        RawOrder order = new RawOrder();

        // 订单号（必填）
        String orderNo = getStr(row, "orderNo");
        if (orderNo == null || orderNo.isBlank()) {
            throw new FieldMappingException(NAME_ORDER_NO, "",
                    "订单号为空", "订单号是必填字段，请检查该行 orderNo 列是否有值；表头是否与模板一致");
        }
        order.setOrderNo(orderNo);

        // 平台（可选）
        order.setPlatform(getStr(row, "platform"));

        // 店铺ID（可选）
        order.setShopId(getStr(row, "shopId"));

        // 币种（必填，支持中英文、全角字符、带"币"字等）
        String currency = getStr(row, "currency");
        if (currency == null || currency.isBlank()) {
            throw new FieldMappingException(NAME_CURRENCY, "",
                    "币种为空", "币种是必填字段（如 USD / CNY / EUR），请检查该行 currency 列是否有值");
        }
        String normalizedCurrency = normalizeCurrency(currency);
        if (normalizedCurrency == null) {
            throw new FieldMappingException(NAME_CURRENCY, truncate(currency),
                    "无法识别的币种：" + truncate(currency),
                    "请使用标准币种代码：CNY/USD/EUR/HKD/JPY/GBP/AUD/CAD/SGD 等；或中文：人民币/美元/欧元/港币/日元");
        }
        order.setCurrency(normalizedCurrency);

        // 金额（必填）
        String amountRaw = row.get("amount");
        BigDecimal amount = parseDecimalStrict(amountRaw, NAME_AMOUNT, true);
        order.setAmount(amount);

        // 手续费（可选，但若填了非数字则报错）
        BigDecimal fee = parseDecimalStrict(row.get("fee"), NAME_FEE, false);
        order.setFee(fee);

        // 结算金额（可选）
        BigDecimal settleAmount = parseDecimalStrict(row.get("settleAmount"), NAME_SETTLE_AMOUNT, false);
        order.setSettleAmount(settleAmount);

        // 订单时间（必填）
        String orderTimeRaw = row.get("orderTime");
        LocalDateTime orderTime = parseDateTimeStrict(orderTimeRaw, NAME_ORDER_TIME, true);
        order.setOrderTime(orderTime);

        // 结算时间（可选）
        LocalDateTime settleTime = parseDateTimeStrict(row.get("settleTime"), NAME_SETTLE_TIME, false);
        order.setSettleTime(settleTime);

        return order;
    }

    /**
     * 币种标准化：支持中文、英文、全角字符、带"币"字等。
     * <p>
     * 示例：
     * <ul>
     *   <li>"USD" / "usd" / "ＵＳＤ" → "USD"</li>
     *   <li>"美元" → "USD"</li>
     *   <li>"人民币" / "CNY" / "RMB" → "CNY"</li>
     *   <li>"日元" / "JPY" → "JPY"</li>
     * </ul>
     */
    private String normalizeCurrency(String raw) {
        if (raw == null) return null;
        // 去除空白、全角空格、括号等（不删除"币"字，因 switch 需要匹配"人民币""港币"等完整名称）
        String cleaned = raw.replaceAll("[\\s\\u3000（）()]", "").trim();
        // 全角字符转半角
        cleaned = halfWidth(cleaned);
        // 中文 → 标准代码
        switch (cleaned) {
            case "人民币":
            case "RMB":
            case "RMB￥":
                return "CNY";
            case "美元":
                return "USD";
            case "欧元":
                return "EUR";
            case "港币":
            case "港元":
                return "HKD";
            case "日元":
            case "日圆":
                return "JPY";
            case "英镑":
                return "GBP";
            case "澳元":
            case "澳大利亚元":
                return "AUD";
            case "加元":
            case "加拿大元":
                return "CAD";
            case "新加坡元":
            case "新币":
                return "SGD";
            default:
                // 尝试作为英文代码
                String upper = cleaned.toUpperCase(Locale.ROOT);
                if (SUPPORTED_CURRENCIES.contains(upper)) {
                    return upper;
                }
                return null;
        }
    }

    /** 全角字符转半角（ASCII 可见字符范围） */
    private String halfWidth(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c >= '\uFF01' && c <= '\uFF5E') {
                sb.append((char) (c - 0xFEE0));
            } else if (c == '\u3000') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String getStr(Map<String, String> row, String key) {
        String v = row.get(key);
        return v == null ? null : v.trim();
    }

    private BigDecimal getDecimal(Map<String, String> row, String key) {
        String v = row.get(key);
        if (v == null || v.isBlank()) return null;
        try {
            // 去掉千分位逗号
            String cleaned = v.replace(",", "").trim();
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            log.warn("[RowMapper] 字段 {} 值 {} 转 BigDecimal 失败：{}", key, v, e.getMessage());
            return null;
        }
    }

    private LocalDateTime getDateTime(Map<String, String> row, String key) {
        String v = row.get(key);
        if (v == null || v.isBlank()) return null;
        String cleaned = v.trim();
        // 优先按含时间格式解析
        for (DateTimeFormatter fmt : DATETIME_FORMATS) {
            try {
                return LocalDateTime.parse(cleaned, fmt);
            } catch (Exception ignored) {
                // 尝试下一种格式
            }
        }
        // 再按仅日期格式解析，时间补 00:00:00
        for (DateTimeFormatter fmt : DATE_ONLY_FORMATS) {
            try {
                return java.time.LocalDate.parse(cleaned, fmt).atStartOfDay();
            } catch (Exception ignored) {
                // 尝试下一种格式
            }
        }
        log.warn("[RowMapper] 字段 {} 值 {} 解析时间失败", key, v);
        return null;
    }

    /**
     * 严格模式 BigDecimal 解析：必填为空 / 格式错误抛 FieldMappingException。
     * <p>
     * 容错策略：
     * <ul>
     *   <li>去除千分位逗号（1,500.00 → 1500.00）</li>
     *   <li>去除常见货币符号（¥ $ € £ ＄ ￥）及全角符号</li>
     *   <li>去除前后空白与全角空格</li>
     *   <li>支持"元"等中文单位后缀</li>
     *   <li>支持括号表示负数（会计格式 (1500.00) → -1500.00）</li>
     * </ul>
     *
     * @param raw       原始字符串
     * @param fieldName 字段中文名（错误时使用）
     * @param required  是否必填
     */
    private BigDecimal parseDecimalStrict(String raw, String fieldName, boolean required) {
        if (raw == null || raw.isBlank()) {
            if (required) {
                throw new FieldMappingException(fieldName, "",
                        fieldName + "为空", fieldName + "是必填字段，请检查该列是否有值");
            }
            return null;
        }
        String cleaned = raw.trim();
        // 全角字符转半角
        cleaned = halfWidth(cleaned);
        // 去除常见货币符号（中英文、全角半角）
        cleaned = cleaned.replaceAll("[¥$€£＄￥￥]", "").trim();
        // 去除中文单位后缀（元、圆、美元等）
        cleaned = cleaned.replaceAll("[元圆]$", "").trim();
        // 括号表示负数（会计格式）
        boolean negative = false;
        if (cleaned.startsWith("(") && cleaned.endsWith(")")) {
            negative = true;
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        // 去除千分位逗号
        cleaned = cleaned.replace(",", "").trim();
        try {
            BigDecimal result = new BigDecimal(cleaned);
            return negative ? result.negate() : result;
        } catch (NumberFormatException e) {
            throw new FieldMappingException(fieldName, truncate(raw),
                    fieldName + "格式错误：" + truncate(raw),
                    "请检查该列是否为纯数字；可包含小数点与千分位逗号（如 1,500.00），但不要包含文字、单位或货币符号");
        }
    }

    /**
     * 严格模式时间解析：必填为空 / 格式错误抛 FieldMappingException。
     * <p>
     * 容错策略：
     * <ul>
     *   <li>全角字符转半角</li>
     *   <li>去除前后空白与全角空格</li>
     *   <li>支持 ISO 格式带 T（2026-07-01T10:00:00）</li>
     *   <li>支持 yyyy.MM.dd、yyyyMMdd、yyyy/M/d 等单位数格式</li>
     *   <li>自动截断毫秒部分（.000）</li>
     *   <li>常见无效值 N/A、null、- → 报错并提示</li>
     * </ul>
     */
    private LocalDateTime parseDateTimeStrict(String raw, String fieldName, boolean required) {
        if (raw == null || raw.isBlank()) {
            if (required) {
                throw new FieldMappingException(fieldName, "",
                        fieldName + "为空", fieldName + "是必填字段，请检查该列是否有值");
            }
            return null;
        }
        String cleaned = halfWidth(raw).trim();
        // 去除 ISO 格式中的 T 分隔符（2026-07-01T10:00:00 → 2026-07-01 10:00:00）
        cleaned = cleaned.replace("T", " ");
        // 去除毫秒部分（仅对 datetime 格式 ".123"，不影响以 . 为分隔符的日期如 2026.8.15 10:00）
        int spaceIdx = cleaned.indexOf(' ');
        int dotIdx = cleaned.indexOf('.');
        if (dotIdx > 0 && spaceIdx > 0 && dotIdx > spaceIdx) {
            cleaned = cleaned.substring(0, dotIdx);
        }
        // 常见无效值快速判断
        String lower = cleaned.toLowerCase(Locale.ROOT);
        if (lower.equals("n/a") || lower.equals("null") || lower.equals("-") || lower.equals("na")) {
            throw new FieldMappingException(fieldName, truncate(raw),
                    fieldName + "为无效值：" + truncate(raw),
                    "请填写有效日期（如 2026-07-01 或 2026/07/01 10:00:00），不要使用 N/A、null、- 等占位符");
        }
        for (DateTimeFormatter fmt : DATETIME_FORMATS) {
            try {
                return LocalDateTime.parse(cleaned, fmt);
            } catch (Exception ignored) {
                // 尝试下一种格式
            }
        }
        for (DateTimeFormatter fmt : DATE_ONLY_FORMATS) {
            try {
                return java.time.LocalDate.parse(cleaned, fmt).atStartOfDay();
            } catch (Exception ignored) {
                // 尝试下一种格式
            }
        }
        throw new FieldMappingException(fieldName, truncate(raw),
                fieldName + "格式错误：" + truncate(raw),
                "支持的时间格式：yyyy-MM-dd HH:mm:ss、yyyy-MM-dd、yyyy/MM/dd、yyyy.MM.dd、yyyyMMdd 等；请检查该列是否有非日期字符");
    }

    /** 截断过长的原值，避免错误明细 UI 失控 */
    private String truncate(String v) {
        if (v == null) return "";
        return v.length() > 50 ? v.substring(0, 50) + "..." : v;
    }
}
