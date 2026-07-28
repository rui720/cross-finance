-- ============================================================
-- 账单导入清洗引擎重构 - 表结构迁移脚本
-- 方案 B + 方案 D：规则引擎 + AI 智能字段识别
-- ============================================================
-- 说明：
--   1. 新增 import_template：字段映射模板（可由 AI 自动识别生成）
--   2. 新增 import_batch：导入批次状态机（IMPORTED/CLEANING/CLEANED/FAILED）
--   3. raw_order 表增加 clean_status / clean_errors / clean_time 字段
--      并将 settle_time 语义归还给原始结算时间，新增 reconcile_status 字段
-- ============================================================

USE `cross_finance`;

SET NAMES utf8mb4;

-- ------------------------------------------------------------
-- 1. 导入模板表（字段映射 + 清洗规则配置）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `import_template`;
CREATE TABLE `import_template` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `template_name`  VARCHAR(128) NOT NULL COMMENT '模板名称（如：Amazon 平台账单标准模板）',
    `platform`       VARCHAR(64)  DEFAULT NULL COMMENT '适配平台（Amazon/Shopee/eBay/Rakuten/通用）',
    `source_type`    VARCHAR(16)  NOT NULL DEFAULT 'PLATFORM' COMMENT '数据来源：PLATFORM/BANK/MANUAL',
    `file_type`      VARCHAR(16)  NOT NULL DEFAULT 'EXCEL' COMMENT '文件类型：EXCEL/CSV',
    `column_mapping` TEXT         NOT NULL COMMENT '字段映射 JSON：{"orderNo":"订单号","amount":"金额",...}',
    `clean_rules`    VARCHAR(512) DEFAULT NULL COMMENT '清洗规则 Bean 名列表，逗号分隔，如：trimRule,defaultCurrencyRule,currencyConvertRule,filterInvalidRule,deduplicateRule',
    `header_row`     INT          NOT NULL DEFAULT 1 COMMENT '表头所在行号（从 1 开始）',
    `sheet_no`       INT          NOT NULL DEFAULT 0 COMMENT '解析的 sheet 索引（0 开始）',
    `ai_generated`   TINYINT      NOT NULL DEFAULT 0 COMMENT '是否 AI 自动识别生成：0 否，1 是',
    `remark`         VARCHAR(256) DEFAULT NULL COMMENT '备注',
    `status`         TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0 禁用，1 启用',
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `create_by`      BIGINT       DEFAULT NULL COMMENT '创建人 ID',
    `update_by`      BIGINT       DEFAULT NULL COMMENT '更新人 ID',
    `deleted`        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_platform` (`platform`),
    KEY `idx_source_type` (`source_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='导入模板配置表（字段映射 + 清洗规则）';

-- ------------------------------------------------------------
-- 2. 导入批次表（状态机 + 错误明细）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `import_batch`;
CREATE TABLE `import_batch` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `batch_no`      VARCHAR(64)  NOT NULL COMMENT '批次号（UUID）',
    `template_id`   BIGINT       DEFAULT NULL COMMENT '使用的导入模板 ID',
    `file_name`     VARCHAR(256) DEFAULT NULL COMMENT '上传文件名',
    `source_type`   VARCHAR(16)  NOT NULL DEFAULT 'PLATFORM' COMMENT '数据来源：PLATFORM/BANK/MANUAL',
    `status`        VARCHAR(16)  NOT NULL DEFAULT 'IMPORTED' COMMENT '批次状态：IMPORTED/CLEANING/CLEANED/FAILED',
    `total_count`   INT          NOT NULL DEFAULT 0 COMMENT '总记录数',
    `success_count` INT          NOT NULL DEFAULT 0 COMMENT '清洗成功数',
    `failed_count`  INT          NOT NULL DEFAULT 0 COMMENT '清洗失败数',
    `error_detail`  TEXT         DEFAULT NULL COMMENT '错误明细 JSON：[{row:3,orderNo:"X",reason:"金额为空"},...]',
    `error_msg`     VARCHAR(512) DEFAULT NULL COMMENT '批次级错误信息',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `create_by`     BIGINT       DEFAULT NULL COMMENT '创建人 ID',
    `update_by`     BIGINT       DEFAULT NULL COMMENT '更新人 ID',
    `deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，1 已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_batch_no` (`batch_no`),
    KEY `idx_template_id` (`template_id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='导入批次状态表';

-- ------------------------------------------------------------
-- 3. raw_order 表增加清洗状态相关字段
-- ------------------------------------------------------------
ALTER TABLE `raw_order`
    ADD COLUMN `clean_status`     TINYINT      NOT NULL DEFAULT 0 COMMENT '清洗状态：0 未清洗，1 已清洗，2 清洗失败' AFTER `batch_no`,
    ADD COLUMN `clean_errors`     VARCHAR(512) DEFAULT NULL COMMENT '清洗错误信息（行级）' AFTER `clean_status`,
    ADD COLUMN `clean_time`       DATETIME     DEFAULT NULL COMMENT '清洗完成时间' AFTER `clean_errors`,
    ADD COLUMN `reconcile_status` TINYINT      NOT NULL DEFAULT 0 COMMENT '对账状态：0 未对账，1 已对账，2 差异' AFTER `clean_time`;

-- 索引：清洗状态查询
ALTER TABLE `raw_order`
    ADD KEY `idx_clean_status` (`clean_status`),
    ADD KEY `idx_reconcile_status` (`reconcile_status`);

-- ------------------------------------------------------------
-- 4. 内置默认模板（通用平台账单模板，对应原 @ExcelProperty 中文列名）
-- ------------------------------------------------------------
INSERT INTO `import_template` (`template_name`, `platform`, `source_type`, `file_type`, `column_mapping`, `clean_rules`, `header_row`, `sheet_no`, `ai_generated`, `remark`, `status`)
VALUES (
    '通用平台账单模板（默认）',
    NULL,
    'PLATFORM',
    'EXCEL',
    '{"orderNo":"订单号","platform":"平台","shopId":"店铺ID","currency":"币种","amount":"金额","fee":"平台费","settleAmount":"结算金额","orderTime":"下单时间","settleTime":"结算时间"}',
    'trimRule,defaultCurrencyRule,filterInvalidRule,currencyConvertRule,deduplicateRule',
    1,
    0,
    0,
    '系统默认模板，对应原始 @ExcelProperty 中文字段映射',
    1
);

INSERT INTO `import_template` (`template_name`, `platform`, `source_type`, `file_type`, `column_mapping`, `clean_rules`, `header_row`, `sheet_no`, `ai_generated`, `remark`, `status`)
VALUES (
    '通用银行流水模板（默认）',
    NULL,
    'BANK',
    'EXCEL',
    '{"orderNo":"订单号","platform":"平台","shopId":"店铺ID","currency":"币种","amount":"金额","fee":"平台费","settleAmount":"结算金额","orderTime":"下单时间","settleTime":"结算时间"}',
    'trimRule,defaultCurrencyRule,filterInvalidRule,deduplicateRule',
    1,
    0,
    0,
    '系统默认银行流水模板',
    1
);
