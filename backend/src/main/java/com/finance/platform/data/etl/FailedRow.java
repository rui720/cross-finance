package com.finance.platform.data.etl;

/**
 * 导入失败行明细（结构化）
 * <p>
 * 用于账单 / 银行流水 / 额外费用等表格导入时，按行收集错误并给出修复建议。
 * 前端失败明细对话框根据本结构展示：行号 / 字段名 / 原值 / 失败原因 / 修复建议。
 * <p>
 * 设计目标：让用户一眼看到"哪一行的哪个字段出了什么问题、该如何修"，
 * 而不是只给一个抽象的失败原因。
 *
 * @param rowNo      行号（从 2 开始，1 为表头）
 * @param fieldName  出错字段中文名（如 "订单号"、"金额"、"订单时间"）；整行级错误填 "（整行）"
 * @param fieldValue 原始值（用于排查，过长时由调用方截断）；无原始值时填空串
 * @param reason     失败原因（如 "金额格式错误"、"必填字段为空"）
 * @param suggestion 修复建议（如 "请检查该列是否为纯数字，去除货币符号、千分位逗号等"）
 */
public record FailedRow(
        int rowNo,
        String fieldName,
        String fieldValue,
        String reason,
        String suggestion
) {}
