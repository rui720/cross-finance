package com.finance.platform.data.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.finance.platform.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 导入模板配置实体
 * <p>
 * 存储字段映射 + 清洗规则配置，可由 AI 自动识别生成或人工维护。
 * 替代原 RawOrder 上硬编码的 @ExcelProperty 中文字段映射。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("import_template")
public class ImportTemplate extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 模板名称 */
    private String templateName;

    /** 适配平台（可空表示通用） */
    private String platform;

    /** 数据来源：PLATFORM/BANK/MANUAL */
    private String sourceType;

    /** 文件类型：EXCEL/CSV */
    private String fileType;

    /** 字段映射 JSON：{"orderNo":"订单号","amount":"金额",...} */
    private String columnMapping;

    /** 清洗规则 Bean 名列表，逗号分隔 */
    private String cleanRules;

    /** 表头所在行号（从 1 开始） */
    private Integer headerRow;

    /** 解析的 sheet 索引（0 开始） */
    private Integer sheetNo;

    /** 是否 AI 自动识别生成：0 否，1 是 */
    private Integer aiGenerated;

    /** 备注 */
    private String remark;

    /** 状态：0 禁用，1 启用 */
    private Integer status;

    /** 是否默认模板（常量）：0 否，1 是。默认模板不可删除，用户删除自定义模板后默认模板自动生效 */
    @TableField("is_default")
    private Integer isDefault;
}
