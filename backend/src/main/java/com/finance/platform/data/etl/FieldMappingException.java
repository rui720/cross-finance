package com.finance.platform.data.etl;

import lombok.Getter;

/**
 * 字段映射异常：携带结构化的字段错误信息（字段名 / 原值 / 修复建议）。
 * <p>
 * 由 {@link com.finance.platform.data.etl.parser.RawOrderRowMapper#mapStrict}、
 * {@link com.finance.platform.data.service.ExtraCostService#mapRow} 等行映射器在
 * 字段解析失败 / 必填为空 / 格式不符时抛出，调用方捕获后转换为 {@link FailedRow}。
 * <p>
 * 与 {@link com.finance.platform.common.exception.BusinessException} 的区别：
 * 本异常携带字段级元信息，便于按行收集错误并向前端用户给出精确修复指引。
 */
@Getter
public class FieldMappingException extends RuntimeException {

    /** 出错字段中文名 */
    private final String fieldName;
    /** 原始值（用于排查） */
    private final String fieldValue;
    /** 修复建议 */
    private final String suggestion;

    public FieldMappingException(String fieldName, String fieldValue, String reason, String suggestion) {
        super(reason);
        this.fieldName = fieldName;
        this.fieldValue = fieldValue;
        this.suggestion = suggestion;
    }
}
