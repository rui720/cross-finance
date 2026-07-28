-- ============================================================
-- 12. 利润报表增强：店铺维度 / 成本结构拆分 / 对账数据 / 订单时间
-- ============================================================
-- 变更内容：
--   1. period 字段扩容 VARCHAR(8) → VARCHAR(32)，支持日期范围模式 "2026-08-15~2026-09-15"
--   2. 新增 shop_id 店铺维度字段（A6）
--   3. 新增 order_time 订单时间字段，用于日/周趋势聚合（A6）
--   4. 新增 fee_cost / shared_cost / direct_cost 成本结构拆分（A5）
--   5. 新增 reconcile_status / reconcile_type / actual_received_amount 对账数据（A4）
--   6. 新增对应索引
-- 注意：cost_amount 总成本语义不变 = fee_cost + shared_cost + direct_cost
--       历史数据需重新核算才能填充新字段，历史 cost_amount 保留不动

SET NAMES utf8mb4;

-- 1. period 扩容（支持 RANGE 模式）
ALTER TABLE `profit_report` MODIFY COLUMN `period` VARCHAR(32) NOT NULL COMMENT '核算周期：月份模式如 202607，范围模式如 2026-08-15~2026-09-15';

-- 2. 新增字段（IF NOT EXISTS 兼容性写法，MySQL 8.0.29+ 支持，低版本需去掉 IF NOT EXISTS）
-- 店铺维度
ALTER TABLE `profit_report` ADD COLUMN `shop_id` VARCHAR(64) DEFAULT NULL COMMENT '店铺 ID' AFTER `platform`;

-- 订单时间
ALTER TABLE `profit_report` ADD COLUMN `order_time` DATETIME DEFAULT NULL COMMENT '订单时间（用于日/周趋势聚合）' AFTER `profit_rate`;

-- 成本结构拆分
ALTER TABLE `profit_report` ADD COLUMN `fee_cost` DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '平台费（该订单自身的平台费，直接归属）' AFTER `cny_amount`;
ALTER TABLE `profit_report` ADD COLUMN `shared_cost` DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '公共分摊成本（无订单号的额外费用按策略分摊）' AFTER `fee_cost`;
ALTER TABLE `profit_report` ADD COLUMN `direct_cost` DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '直接成本（按订单号归集的额外费用）' AFTER `shared_cost`;
ALTER TABLE `profit_report` MODIFY COLUMN `cost_amount` DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '总成本 = fee_cost + shared_cost + direct_cost';

-- 对账数据
ALTER TABLE `profit_report` ADD COLUMN `reconcile_status` TINYINT NOT NULL DEFAULT 0 COMMENT '对账状态：0 未对账，1 已对账，2 差异' AFTER `order_time`;
ALTER TABLE `profit_report` ADD COLUMN `reconcile_type` VARCHAR(20) DEFAULT NULL COMMENT '对账结果类型：MATCHED/AMOUNT_DIFF/PLATFORM_ONLY/BANK_ONLY' AFTER `reconcile_status`;
ALTER TABLE `profit_report` ADD COLUMN `actual_received_amount` DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '实际到账金额（CNY，未到账为 0）' AFTER `reconcile_type`;

-- 3. 新增索引
ALTER TABLE `profit_report` ADD INDEX `idx_shop_id` (`shop_id`);
ALTER TABLE `profit_report` ADD INDEX `idx_order_time` (`order_time`);
ALTER TABLE `profit_report` ADD INDEX `idx_reconcile_status` (`reconcile_status`);
