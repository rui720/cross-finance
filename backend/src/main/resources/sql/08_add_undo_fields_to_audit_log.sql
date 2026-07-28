-- 08_add_undo_fields_to_audit_log.sql
-- 为审计日志表新增 old_value（操作前数据快照）和 undone（是否已撤销）字段，支撑撤销功能
SET NAMES utf8mb4;

ALTER TABLE `sys_audit_log`
    ADD COLUMN `old_value` TEXT DEFAULT NULL COMMENT '操作前数据快照（JSON，用于撤销恢复）' AFTER `params`,
    ADD COLUMN `undone` TINYINT NOT NULL DEFAULT 0 COMMENT '是否已撤销：0 未撤销，1 已撤销' AFTER `status`;
