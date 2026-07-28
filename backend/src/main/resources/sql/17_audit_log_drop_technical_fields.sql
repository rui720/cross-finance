-- ============================================================
-- 跨境金融平台 - sys_audit_log 表清理技术性字段迁移脚本
-- 执行前请备份数据库
-- 创建日期：2026-07-26
-- 变更内容：
--   删除以下非业务相关字段：
--     * method     请求方法（HTTP 方法 + URI，编程相关）
--     * params     请求参数（JSON 序列化，编程相关）
--     * ip         请求 IP（技术性，业务人员无感知）
--     * cost_time  耗时ms（性能指标，非业务）
--   保留字段：user_id, username, operation, old_value, status, undone,
--            error_msg, create_time, update_time, create_by, update_by, deleted
-- 设计原因：
--   审计日志面向业务人员查看操作历史，技术字段对非编程人员是无用信息，
--   且占用存储空间。保留 operation（操作描述）+ old_value（操作前快照）即可
--   完整还原"谁在什么时候做了什么、操作前是什么数据、是否成功、是否撤销"。
-- ============================================================

SET NAMES utf8mb4;
USE `cross_finance`;

-- 安全删除字段（若存在）
SET @sql := NULL;
SELECT GROUP_CONCAT(CONCAT('DROP COLUMN `', column_name, '`') SEPARATOR ', ')
INTO @sql
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'sys_audit_log'
  AND column_name IN ('method', 'params', 'ip', 'cost_time');

SET @sql := IF(@sql IS NOT NULL, CONCAT('ALTER TABLE `sys_audit_log` ', @sql), NULL);

SET @exec := IF(@sql IS NOT NULL, @sql, 'SELECT 1 AS no_change');
PREPARE stmt FROM @exec;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 验证
SHOW COLUMNS FROM `sys_audit_log`;
