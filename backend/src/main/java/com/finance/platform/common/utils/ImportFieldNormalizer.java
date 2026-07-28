package com.finance.platform.common.utils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.SignStyle;
import java.time.temporal.ChronoField;
import java.util.Locale;
import java.util.Set;

/**
 * 导入字段标准化工具
 * <p>
 * 统一四个导入页面（平台账单、银行流水、额外费用、历史汇率）的字段解析逻辑，
 * 确保日期、金额、币种等通用字段在不同导入入口的容错策略保持一致。
 * <p>
 * 所有方法均为容错模式（解析失败返回 null），由调用方决定是否抛出业务异常。
 */
public final class ImportFieldNormalizer {

    private ImportFieldNormalizer() {}

    /** 支持的币种代码（大小写不敏感） */
    public static final Set<String> SUPPORTED_CURRENCIES = Set.of(
            "CNY", "USD", "EUR", "HKD", "JPY", "GBP", "AUD", "CAD", "SGD", "KRW", "RUB", "BRL", "INR");

    /** 含时间部分的格式（按 LocalDateTime 解析） */
    private static final DateTimeFormatter[] DATETIME_FORMATS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm"),
            // 宽松格式：支持单位数月/日/时/分（如 2026/7/31 10:00），Excel/CSV 常见
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

    /** 仅日期格式（解析后时间补 00:00:00） */
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

    /**
     * 全角字符转半角（ASCII 可见字符范围）。
     * 用于处理用户从中文输入法或某些平台复制粘贴导致的全角字符。
     */
    public static String halfWidth(String s) {
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
     *
     * @param raw 原始币种字符串
     * @return 标准币种代码，无法识别返回 null
     */
    public static String normalizeCurrency(String raw) {
        if (raw == null) return null;
        // 去除空白、全角空格、括号等（不删除"币"字，因 switch 需要匹配"人民币""港币"等完整名称）
        String cleaned = raw.replaceAll("[\\s\\u3000（）()]", "").trim();
        // 全角字符转半角
        cleaned = halfWidth(cleaned);
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
                String upper = cleaned.toUpperCase(Locale.ROOT);
                if (SUPPORTED_CURRENCIES.contains(upper)) {
                    return upper;
                }
                return null;
        }
    }

    /**
     * 金额解析（容错模式）。
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
     * @param raw 原始字符串
     * @return 解析后的 BigDecimal，无法解析返回 null
     */
    public static BigDecimal parseDecimal(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String cleaned = halfWidth(raw).trim();
        // 去除常见货币符号（中英文、全角半角）
        cleaned = cleaned.replaceAll("[¥$€£＄￥]", "").trim();
        // 去除中文单位后缀（元、圆）
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
            return null;
        }
    }

    /**
     * 日期解析（容错模式，返回 LocalDate）。
     * <p>
     * 支持格式：yyyy-MM-dd、yyyy/MM/dd、yyyy.MM.dd、yyyyMMdd 及单位数月/日（如 2026/7/31）。
     * 含时间部分的字符串会自动截取前 10 位日期部分。
     *
     * @param raw 原始字符串
     * @return 解析后的 LocalDate，无法解析返回 null
     */
    public static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String cleaned = halfWidth(raw).trim();
        // 去除 ISO 格式中的 T 分隔符
        cleaned = cleaned.replace("T", " ");
        // 去除毫秒部分（仅对 datetime 格式 ".123"，不影响以 . 为分隔符的日期如 2026.8.15）
        int spaceIdx = cleaned.indexOf(' ');
        int dotIdx = cleaned.indexOf('.');
        if (dotIdx > 0 && spaceIdx > 0 && dotIdx > spaceIdx) {
            cleaned = cleaned.substring(0, dotIdx);
        }
        // 截断含时间的部分（取前 10 位 yyyy-MM-dd 或 yyyy/MM/dd）
        if (cleaned.length() > 10) {
            cleaned = cleaned.substring(0, 10);
        }
        for (DateTimeFormatter fmt : DATE_ONLY_FORMATS) {
            try {
                return LocalDate.parse(cleaned, fmt);
            } catch (Exception ignored) {
                // 尝试下一种格式
            }
        }
        return null;
    }

    /**
     * 日期时间解析（容错模式，返回 LocalDateTime）。
     * <p>
     * 优先按含时间格式解析，失败后按仅日期格式解析（时间补 00:00:00）。
     *
     * @param raw 原始字符串
     * @return 解析后的 LocalDateTime，无法解析返回 null
     */
    public static LocalDateTime parseDateTime(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String cleaned = halfWidth(raw).trim();
        // 去除 ISO 格式中的 T 分隔符
        cleaned = cleaned.replace("T", " ");
        // 去除毫秒部分（仅对 datetime 格式 ".123"，不影响以 . 为分隔符的日期如 2026.8.15 10:00）
        int spaceIdx = cleaned.indexOf(' ');
        int dotIdx = cleaned.indexOf('.');
        if (dotIdx > 0 && spaceIdx > 0 && dotIdx > spaceIdx) {
            cleaned = cleaned.substring(0, dotIdx);
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
                return LocalDate.parse(cleaned, fmt).atStartOfDay();
            } catch (Exception ignored) {
                // 尝试下一种格式
            }
        }
        return null;
    }

    /** 截断过长的原值，避免错误明细 UI 失控 */
    public static String truncate(String v) {
        if (v == null) return "";
        return v.length() > 50 ? v.substring(0, 50) + "..." : v;
    }
}
