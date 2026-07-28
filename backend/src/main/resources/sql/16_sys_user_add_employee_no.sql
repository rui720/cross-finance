-- ============================================================
-- 跨境金融平台 - sys_user 表新增员工工号字段迁移脚本
-- 执行前请备份数据库
-- 创建日期：2026-07-26
-- 变更内容：
--   1. 新增 employee_no 字段（员工工号，业务唯一标识）
--   2. 为现有用户回填 employee_no（EMP + 年份 + 4 位顺序号）
--   3. 删除 uk_username 唯一索引（若仍存在），用户名不再作为唯一约束
--   4. 工号唯一性由应用层在 deleted=0 范围内校验，避免逻辑删除冲突
-- 设计原因：
--   * 数据库唯一索引不区分 deleted 字段，逻辑删除的记录仍占用唯一键
--   * 改用 employee_no 作为业务唯一标识（一人一号），username 仅作登录凭据
--   * 撤销删除时校验 employee_no/phone/email 在 deleted=0 范围内是否冲突
-- ============================================================

SET NAMES utf8mb4;
USE `cross_finance`;

-- ------------------------------------------------------------
-- 步骤 1：删除 uk_username 唯一索引（若存在）
-- ------------------------------------------------------------
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

-- ------------------------------------------------------------
-- 步骤 2：新增 employee_no 字段（若不存在）
-- ------------------------------------------------------------
SET @col_exist := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'sys_user'
      AND column_name = 'employee_no'
);
SET @sql := IF(@col_exist = 0,
    'ALTER TABLE `sys_user` ADD COLUMN `employee_no` VARCHAR(32) DEFAULT NULL COMMENT ''员工工号（业务唯一标识，EMP+年份+顺序号）'' AFTER `username`',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 步骤 3：为现有无工号用户回填 employee_no
--   规则：EMP + 当前年份 + 4 位顺序号（基于 id 升序）
--   示例：EMP20260001、EMP20260002 ...
-- ------------------------------------------------------------
UPDATE `sys_user` u
JOIN (
    SELECT id,
           CONCAT('EMP', YEAR(NOW()), LPAD(ROW_NUMBER() OVER (ORDER BY id), 4, '0')) AS new_no
    FROM `sys_user`
    WHERE employee_no IS NULL
) t ON u.id = t.id
SET u.employee_no = t.new_no;

-- ------------------------------------------------------------
-- 步骤 4：将 employee_no 设为 NOT NULL（回填完成后）
-- ------------------------------------------------------------
ALTER TABLE `sys_user` MODIFY COLUMN `employee_no` VARCHAR(32) NOT NULL COMMENT '员工工号（业务唯一标识，EMP+年份+顺序号）';

-- ------------------------------------------------------------
-- 步骤 5：新增普通索引（不加唯一索引，避免逻辑删除冲突）
-- ------------------------------------------------------------
SET @idx_exist := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'sys_user'
      AND index_name = 'idx_employee_no'
);
SET @sql := IF(@idx_exist = 0,
    'ALTER TABLE `sys_user` ADD INDEX `idx_employee_no` (`employee_no`)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 验证
-- ------------------------------------------------------------
SHOW INDEX FROM `sys_user` WHERE Key_name IN ('uk_username', 'idx_employee_no');
SELECT id, username, employee_no, real_name FROM `sys_user` ORDER BY id LIMIT 20;
