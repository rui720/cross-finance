-- ============================================================
-- 跨境金融平台 - 迁移脚本：额外费用导入功能
-- ============================================================
-- 背景：原系统只支持平台费（raw_order.fee）参与利润核算，
--      物流费、广告费、仓储费等"额外费用"无批量导入入口，
--      也不参与利润核算，是已知的架构断点。
-- 本脚本新增 extra_cost 表，承载额外费用数据，并修复利润核算引擎。
-- 幂等可重复执行（MySQL 8.0 兼容）。
-- ============================================================

SET NAMES utf8mb4;
USE `cross_finance`;

-- ------------------------------------------------------------
-- 1. 额外费用表（extra_cost）
-- ------------------------------------------------------------
-- 设计要点：
-- - cost_type 枚举覆盖跨境业务 10 类主要费用
-- - order_no 可空：填了则直接计入该订单成本；空则进入"公共成本池"按金额占比分摊
-- - period 必填：明确归属核算周期，避免跨期混淆
-- - cny_amount 由后端按 cost_date 当日汇率折算后回填
-- - source 区分导入来源：IMPORT（文件导入）/ MANUAL（手工录入）
CREATE TABLE IF NOT EXISTS `extra_cost` (
    `id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `cost_type`     VARCHAR(32)   NOT NULL COMMENT '费用类型：LOGISTICS/WAREHOUSE/ADVERTISING/CUSTOMS_DUTY/COMMISSION/FX_LOSS/RETURN_LOSS/TRANSACTION_FEE/PACKAGING/OTHER',
    `amount`        DECIMAL(18,4) NOT NULL COMMENT '原始金额',
    `currency`      VARCHAR(8)    NOT NULL DEFAULT 'CNY' COMMENT '币种',
    `cny_amount`    DECIMAL(18,4) DEFAULT NULL COMMENT '人民币金额（折算后，由后端回填）',
    `period`        VARCHAR(8)    NOT NULL COMMENT '核算周期，如 202607',
    `order_no`      VARCHAR(64)   DEFAULT NULL COMMENT '关联订单号（空则进入公共成本池分摊）',
    `payee`         VARCHAR(128)  DEFAULT NULL COMMENT '收款方',
    `cost_date`     DATE          NOT NULL COMMENT '费用发生日期',
    `remark`        VARCHAR(512)  DEFAULT NULL COMMENT '备注',
    `source`        VARCHAR(16)   NOT NULL DEFAULT 'IMPORT' COMMENT '来源：IMPORT/MANUAL',
    `batch_no`      VARCHAR(64)   DEFAULT NULL COMMENT '导入批次号',
    `status`        TINYINT       NOT NULL DEFAULT 1 COMMENT '状态：0 已作废，1 生效',
    `create_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `create_by`     BIGINT        DEFAULT NULL COMMENT '创建人 ID',
    `update_by`     BIGINT        DEFAULT NULL COMMENT '更新人 ID',
    `deleted`       TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_period` (`period`),
    KEY `idx_cost_type` (`cost_type`),
    KEY `idx_order_no` (`order_no`),
    KEY `idx_batch_no` (`batch_no`),
    KEY `idx_cost_date` (`cost_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='额外费用表（物流/广告/仓储等）';

-- ------------------------------------------------------------
-- 2. 插入默认导入模板（额外费用模板）
-- ------------------------------------------------------------
INSERT INTO `import_template` (`template_name`, `platform`, `source_type`, `file_type`, `column_mapping`, `clean_rules`, `header_row`, `sheet_no`, `ai_generated`, `remark`, `status`)
SELECT '额外费用默认模板', NULL, 'COST', 'EXCEL',
       '{"costType":"费用类型","amount":"金额","currency":"币种","period":"核算周期","orderNo":"订单号","payee":"收款方","costDate":"费用日期","remark":"备注"}',
       'trimRule,nullCheckRule,numberFormatRule',
       1, 0, 0, '系统默认额外费用导入模板', 1
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `import_template` WHERE `template_name` = '额外费用默认模板' AND `source_type` = 'COST');

-- ------------------------------------------------------------
-- 3. 校验
-- ------------------------------------------------------------
SELECT '=== extra_cost 表结构 ===' AS info;
SHOW COLUMNS FROM `extra_cost` LIKE 'cost_type';

SELECT '=== 额外费用模板 ===' AS info;
SELECT id, template_name, source_type, status FROM `import_template` WHERE `source_type` = 'COST';
