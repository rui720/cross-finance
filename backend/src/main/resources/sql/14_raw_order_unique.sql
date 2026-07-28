-- ============================================================
-- 跨境金融平台 - raw_order 唯一性约束迁移脚本
-- 执行前请备份数据库
-- 创建日期：2026-07-25
-- 变更内容：
--   1. 清理已存在的重复数据（同 order_no + source 保留最早一条）
--   2. 添加 (order_no, source) 唯一索引
-- 注意：
--   - 本脚本可重复执行（幂等）
--   - DELETE 已在上次执行时生效，重复数据已清理
--   - 唯一索引不包含 deleted 字段，逻辑删除的记录仍占用唯一键
--     （即删除后不能重新添加相同 order_no + source，需先物理删除）
-- ============================================================

SET NAMES utf8mb4;
USE `cross_finance`;

-- ------------------------------------------------------------
-- 1. 检查是否还有重复数据
-- ------------------------------------------------------------
SELECT `order_no`, `source`, COUNT(*) AS cnt
FROM `raw_order`
WHERE `deleted` = 0
GROUP BY `order_no`, `source`
HAVING COUNT(*) > 1
ORDER BY cnt DESC;

-- ------------------------------------------------------------
-- 2. 清理重复数据：同 order_no + source 只保留 id 最小的一条
--    （如果上一步已执行过，这里不会删除任何数据）
-- ------------------------------------------------------------
DELETE r1 FROM `raw_order` r1
INNER JOIN (
    SELECT `order_no`, `source`, MIN(`id`) AS min_id
    FROM `raw_order`
    WHERE `deleted` = 0
    GROUP BY `order_no`, `source`
    HAVING COUNT(*) > 1
) r2 ON r1.`order_no` = r2.`order_no`
     AND r1.`source` = r2.`source`
     AND r1.`id` > r2.`min_id`
WHERE r1.`deleted` = 0;

-- ------------------------------------------------------------
-- 3. 安全删除旧索引 idx_order_no（如果存在）
--    MySQL 不支持 DROP INDEX IF EXISTS，用动态 SQL 替代
-- ------------------------------------------------------------
SET @exist := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'raw_order'
      AND index_name = 'idx_order_no'
);
SET @sql := IF(@exist > 0,
    'ALTER TABLE `raw_order` DROP INDEX `idx_order_no`',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 4. 安全添加唯一索引 uk_order_no_source（如果不存在）
-- ------------------------------------------------------------
SET @exist2 := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'raw_order'
      AND index_name = 'uk_order_no_source'
);
SET @sql2 := IF(@exist2 = 0,
    'ALTER TABLE `raw_order` ADD UNIQUE KEY `uk_order_no_source` (`order_no`, `source`)',
    'SELECT 1'
);
PREPARE stmt2 FROM @sql2;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;

-- ------------------------------------------------------------
-- 5. 验证索引
-- ------------------------------------------------------------
SHOW INDEX FROM `raw_order` WHERE Key_name IN ('uk_order_no_source', 'idx_order_no');
