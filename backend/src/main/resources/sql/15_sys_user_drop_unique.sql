-- ============================================================
-- 跨境金融平台 - sys_user 唯一索引调整迁移脚本
-- 执行前请备份数据库
-- 创建日期：2026-07-25
-- 变更内容：
--   删除 sys_user 表的 uk_username 唯一索引
--   原因：MyBatis-Plus 逻辑删除（deleted=1）的记录仍占用唯一键，
--         导致删除用户后无法重新创建同名用户。
--   替代方案：由应用层 SysUserService.createUser() 校验用户名唯一性，
--             仅校验 deleted=0 的记录，逻辑删除的 username 可复用。
-- ============================================================

SET NAMES utf8mb4;
USE `cross_finance`;

-- 安全删除 uk_username 唯一索引（如果存在）
SET @exist := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'sys_user'
      AND index_name = 'uk_username'
);
SET @sql := IF(@exist > 0,
    'ALTER TABLE `sys_user` DROP INDEX `uk_username`',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 验证：uk_username 应不存在
SHOW INDEX FROM `sys_user` WHERE Key_name = 'uk_username';
