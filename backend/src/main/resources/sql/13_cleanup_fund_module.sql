-- ============================================================
-- 跨境金融平台 - 清理已移除的资金风控模块残留数据
-- 执行前请备份数据库
-- 创建日期：2026-07-25
-- 变更内容：
--   1. 删除 approver / cashier 用户（资金风控模块已移除，角色不再使用）
--   2. 清理仍持有 APPROVER / CASHIER 角色的用户角色关联
-- ============================================================

SET NAMES utf8mb4;
USE `cross_finance`;

-- ------------------------------------------------------------
-- 1. 删除 approver / cashier 用户
--    这两个用户是为资金风控审批流程创建的，模块已移除
-- ------------------------------------------------------------
DELETE FROM `sys_user` WHERE `username` IN ('approver', 'cashier');

-- ------------------------------------------------------------
-- 2. 清理仍持有 APPROVER / CASHIER 角色的用户
--    将 role_ids 中的 APPROVER / CASHIER 移除（JSON 数组处理）
--    注意：MySQL 8.0+ 的 JSON_REMOVE 配合 JSON_SEARCH 实现
--    若无用户持有这些角色则此步无影响
-- ------------------------------------------------------------
UPDATE `sys_user`
SET `role_ids` = JSON_REMOVE(
        `role_ids`,
        JSON_UNQUOTE(JSON_SEARCH(`role_ids`, 'one', 'APPROVER'))
     )
WHERE JSON_SEARCH(`role_ids`, 'one', 'APPROVER') IS NOT NULL;

UPDATE `sys_user`
SET `role_ids` = JSON_REMOVE(
        `role_ids`,
        JSON_UNQUOTE(JSON_SEARCH(`role_ids`, 'one', 'CASHIER'))
     )
WHERE JSON_SEARCH(`role_ids`, 'one', 'CASHIER') IS NOT NULL;

-- 验证：查询剩余用户及其角色
SELECT id, username, real_name, role_ids FROM `sys_user` WHERE deleted = 0 ORDER BY id;
