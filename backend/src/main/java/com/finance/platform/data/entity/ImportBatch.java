package com.finance.platform.data.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.finance.platform.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 导入批次实体（状态机）
 * <p>
 * 每次导入生成一条记录，状态流转：IMPORTED → CLEANING → CLEANED / FAILED。
 * 记录总数、成功数、失败数、错误明细，替代原 raw_order 表 GROUP BY batch_no 的隐式状态。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("import_batch")
public class ImportBatch extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 批次号（UUID） */
    private String batchNo;

    /** 使用的导入模板 ID */
    private Long templateId;

    /** 上传文件名 */
    private String fileName;

    /** 数据来源：PLATFORM/BANK/MANUAL */
    private String sourceType;

    /** 批次状态：IMPORTED/CLEANING/CLEANED/FAILED */
    private String status;

    /** 总记录数 */
    private Integer totalCount;

    /** 清洗成功数 */
    private Integer successCount;

    /** 清洗失败数 */
    private Integer failedCount;

    /** 错误明细 JSON */
    private String errorDetail;

    /** 清洗结果汇总 JSON（记录各规则的动作：异常拦截/缺省补全/汇率折算/去重，格式统一不记录） */
    private String cleanSummary;

    /** 批次级错误信息 */
    private String errorMsg;

    /** 状态常量 */
    public static final String STATUS_IMPORTED = "IMPORTED";
    public static final String STATUS_CLEANING = "CLEANING";
    public static final String STATUS_CLEANED = "CLEANED";
    public static final String STATUS_FAILED = "FAILED";
}
