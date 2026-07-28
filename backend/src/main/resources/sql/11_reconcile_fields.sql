-- 11_reconcile_fields.sql
-- 银行对账功能增强：raw_order 表新增对账结果类型和匹配ID字段
-- reconcile_type: 对账结果类型（MATCHED已匹配/AMOUNT_DIFF金额差异/PLATFORM_ONLY未到账/BANK_ONLY不明入账）
-- reconcile_match_id: 匹配的对方记录ID（平台订单记录对应的银行流水ID，或反之）
SET NAMES utf8mb4;

-- 1. 新增 reconcile_type 列
ALTER TABLE `raw_order`
    ADD COLUMN `reconcile_type` VARCHAR(20) DEFAULT NULL
    COMMENT '对账结果类型：MATCHED已匹配/AMOUNT_DIFF金额差异/PLATFORM_ONLY未到账/BANK_ONLY不明入账'
    AFTER `reconcile_status`;

-- 2. 新增 reconcile_match_id 列
ALTER TABLE `raw_order`
    ADD COLUMN `reconcile_match_id` BIGINT DEFAULT NULL
    COMMENT '匹配的对方记录ID'
    AFTER `reconcile_type`;

-- 3. 验证
SHOW COLUMNS FROM `raw_order` LIKE 'reconcile%';
