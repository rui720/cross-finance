-- ============================================================
-- 跨境金融平台 - 迁移脚本：账单导入清洗功能（方案 B+D）
-- ============================================================
-- 背景：方案 B（规则引擎 + 解析器抽象）+ 方案 D（AI 字段识别）实施时
--      新增了 import_batch、import_template 两张表，
--      并给 raw_order 表添加了清洗状态字段，
--      但建表脚本 01_schema.sql 未同步更新，导致线上已有库缺表/缺字段。
-- 本脚本用于增量迁移现有数据库，幂等可重复执行（MySQL 8.0 兼容）。
-- ============================================================

SET NAMES utf8mb4;
USE `cross_finance`;

-- ------------------------------------------------------------
-- 1. raw_order 表补字段：清洗状态 + 对账状态（幂等）
-- ------------------------------------------------------------
-- 通过 information_schema 动态判断列是否存在，避免重复执行报错
DROP PROCEDURE IF EXISTS `add_column_if_not_exists`;
DELIMITER $$
CREATE PROCEDURE `add_column_if_not_exists`(
    IN tbl VARCHAR(64),
    IN col VARCHAR(64),
    IN ddl TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = tbl
          AND COLUMN_NAME = col
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN ', ddl);
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

CALL `add_column_if_not_exists`('raw_order', 'clean_status',
    '`clean_status` TINYINT NOT NULL DEFAULT 0 COMMENT ''清洗状态：0 未清洗，1 已清洗，2 清洗失败'' AFTER `batch_no`');

CALL `add_column_if_not_exists`('raw_order', 'clean_errors',
    '`clean_errors` TEXT DEFAULT NULL COMMENT ''清洗错误信息（行级，JSON）'' AFTER `clean_status`');

CALL `add_column_if_not_exists`('raw_order', 'clean_time',
    '`clean_time` DATETIME DEFAULT NULL COMMENT ''清洗完成时间'' AFTER `clean_errors`');

CALL `add_column_if_not_exists`('raw_order', 'reconcile_status',
    '`reconcile_status` TINYINT NOT NULL DEFAULT 0 COMMENT ''对账状态：0 未对账，1 已对账，2 差异'' AFTER `clean_time`');

DROP PROCEDURE IF EXISTS `add_index_if_not_exists`;
DELIMITER $$
CREATE PROCEDURE `add_index_if_not_exists`(
    IN tbl VARCHAR(64),
    IN idx VARCHAR(64),
    IN cols VARCHAR(128)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = tbl
          AND INDEX_NAME = idx
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD INDEX `', idx, '` (', cols, ')');
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

CALL `add_index_if_not_exists`('raw_order', 'idx_clean_status', '`clean_status`');
CALL `add_index_if_not_exists`('raw_order', 'idx_reconcile_status', '`reconcile_status`');

DROP PROCEDURE IF EXISTS `add_column_if_not_exists`;
DROP PROCEDURE IF EXISTS `add_index_if_not_exists`;

-- ------------------------------------------------------------
-- 2. 导入模板配置表（import_template）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `import_template` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `template_name`  VARCHAR(128) NOT NULL COMMENT '模板名称',
    `platform`       VARCHAR(64)  DEFAULT NULL COMMENT '适配平台（NULL 表示通用）',
    `source_type`    VARCHAR(16)  NOT NULL DEFAULT 'PLATFORM' COMMENT '数据来源：PLATFORM/BANK/MANUAL',
    `file_type`      VARCHAR(16)  DEFAULT NULL COMMENT '文件类型：EXCEL/CSV',
    `column_mapping` TEXT         DEFAULT NULL COMMENT '字段映射 JSON：{"orderNo":"订单号","amount":"金额",...}',
    `clean_rules`    VARCHAR(512) DEFAULT NULL COMMENT '清洗规则 Bean 名列表，逗号分隔',
    `header_row`     INT          NOT NULL DEFAULT 1 COMMENT '表头所在行号（从 1 开始）',
    `sheet_no`       INT          NOT NULL DEFAULT 0 COMMENT '解析的 sheet 索引（0 开始）',
    `ai_generated`   TINYINT      NOT NULL DEFAULT 0 COMMENT '是否 AI 自动识别生成：0 否，1 是',
    `remark`         VARCHAR(512) DEFAULT NULL COMMENT '备注',
    `status`         TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0 禁用，1 启用',
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `create_by`      BIGINT       DEFAULT NULL COMMENT '创建人 ID',
    `update_by`      BIGINT       DEFAULT NULL COMMENT '更新人 ID',
    `deleted`        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_source_type` (`source_type`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='导入模板配置表';

-- ------------------------------------------------------------
-- 3. 导入批次状态机表（import_batch）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `import_batch` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `batch_no`      VARCHAR(64)  NOT NULL COMMENT '批次号（UUID）',
    `template_id`   BIGINT       DEFAULT NULL COMMENT '使用的导入模板 ID',
    `file_name`     VARCHAR(256) DEFAULT NULL COMMENT '上传文件名',
    `source_type`   VARCHAR(16)  NOT NULL DEFAULT 'PLATFORM' COMMENT '数据来源：PLATFORM/BANK/MANUAL',
    `status`        VARCHAR(16)  NOT NULL DEFAULT 'IMPORTED' COMMENT '批次状态：IMPORTED/CLEANING/CLEANED/FAILED',
    `total_count`   INT          NOT NULL DEFAULT 0 COMMENT '总记录数',
    `success_count` INT          NOT NULL DEFAULT 0 COMMENT '清洗成功数',
    `failed_count`  INT          NOT NULL DEFAULT 0 COMMENT '清洗失败数',
    `error_detail`  TEXT         DEFAULT NULL COMMENT '错误明细（JSON）',
    `error_msg`     VARCHAR(1024) DEFAULT NULL COMMENT '批次级错误信息',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `create_by`     BIGINT       DEFAULT NULL COMMENT '创建人 ID',
    `update_by`     BIGINT       DEFAULT NULL COMMENT '更新人 ID',
    `deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，1 已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_batch_no` (`batch_no`),
    KEY `idx_status` (`status`),
    KEY `idx_source_type` (`source_type`),
    KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='导入批次状态机表';

-- ------------------------------------------------------------
-- 4. 插入默认导入模板（幂等：仅在不存在时插入）
-- ------------------------------------------------------------
INSERT INTO `import_template` (`template_name`, `platform`, `source_type`, `file_type`, `column_mapping`, `clean_rules`, `header_row`, `sheet_no`, `ai_generated`, `remark`, `status`)
SELECT '平台账单默认模板', NULL, 'PLATFORM', 'EXCEL',
       '{"orderNo":"订单号","platform":"平台","shopId":"店铺ID","currency":"币种","amount":"金额","fee":"手续费","settleAmount":"结算金额","orderTime":"下单时间","settleTime":"结算时间"}',
       'trimRule,nullCheckRule,numberFormatRule,dateFormatRule',
       1, 0, 0, '系统默认平台账单导入模板', 1
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `import_template` WHERE `template_name` = '平台账单默认模板' AND `source_type` = 'PLATFORM');

INSERT INTO `import_template` (`template_name`, `platform`, `source_type`, `file_type`, `column_mapping`, `clean_rules`, `header_row`, `sheet_no`, `ai_generated`, `remark`, `status`)
SELECT '银行流水默认模板', NULL, 'BANK', 'EXCEL',
       '{"orderNo":"交易流水号","amount":"交易金额","currency":"币种","orderTime":"交易时间","settleTime":"入账时间"}',
       'trimRule,nullCheckRule,numberFormatRule,dateFormatRule',
       1, 0, 0, '系统默认银行流水导入模板', 1
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `import_template` WHERE `template_name` = '银行流水默认模板' AND `source_type` = 'BANK');

-- ------------------------------------------------------------
-- 5. 校验迁移结果
-- ------------------------------------------------------------
SELECT '=== raw_order 表新增字段 ===' AS info;
SHOW COLUMNS FROM `raw_order` LIKE 'clean%';
SHOW COLUMNS FROM `raw_order` LIKE 'reconcile%';

SELECT '=== import_template 表数据 ===' AS info;
SELECT id, template_name, source_type, status FROM `import_template`;

SELECT '=== import_batch 表数据量 ===' AS info;
SELECT COUNT(*) AS batch_count FROM `import_batch`;
