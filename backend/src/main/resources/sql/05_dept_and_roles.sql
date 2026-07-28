-- ============================================================
-- 跨境金融平台 - 部门表 + 角色扩充 迁移脚本
-- 执行前请先执行 01_schema.sql / 02_init_data.sql
-- 创建日期：2026-07-16
-- 变更内容：
--   1. 新建 sys_dept 部门表
--   2. 初始化部门数据（运营/物流/营销/财务/人事/IT）
--   3. 为现有用户回填部门名称关联（通过 dept_id 已建立）
--   4. 新增 1 个系统用户：普通员工 employee
-- ============================================================

SET NAMES utf8mb4;
USE `cross_finance`;

-- ------------------------------------------------------------
-- 1. 部门表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `sys_dept`;
CREATE TABLE `sys_dept` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `parent_id`   BIGINT       NOT NULL DEFAULT 0 COMMENT '父部门 ID，0 表示顶级',
    `dept_name`   VARCHAR(64)  NOT NULL COMMENT '部门名称',
    `dept_code`   VARCHAR(64)  DEFAULT NULL COMMENT '部门编码',
    `sort`        INT          NOT NULL DEFAULT 0 COMMENT '排序（升序）',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0 禁用，1 启用',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `create_by`   BIGINT       DEFAULT NULL COMMENT '创建人 ID',
    `update_by`   BIGINT       DEFAULT NULL COMMENT '更新人 ID',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，1 已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_dept_code` (`dept_code`),
    KEY `idx_parent_id` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='部门表';

-- ------------------------------------------------------------
-- 2. 初始化部门数据
-- ------------------------------------------------------------
INSERT INTO `sys_dept` (`id`, `parent_id`, `dept_name`, `dept_code`, `sort`, `status`) VALUES
(1, 0, '总公司',     'HQ',       0, 1),
(2, 1, '运营部',     'OPS',      1, 1),
(3, 1, '物流部',     'LOGI',     2, 1),
(4, 1, '营销部',     'MKT',      3, 1),
(5, 1, '财务部',     'FIN',      4, 1),
(6, 1, '人事部',     'HR',       5, 1),
(7, 1, 'IT 信息部',  'IT',       6, 1);

-- ------------------------------------------------------------
-- 3. 补充用户：普通员工
--    密码为 BCrypt 加密存储的初始密码，首次登录后请强制修改
--    不指定 id（让自增），避免与已插入的用户冲突
--    使用 ON DUPLICATE KEY UPDATE 保证脚本可重复执行
-- ------------------------------------------------------------
INSERT INTO `sys_user` (`username`, `password`, `real_name`, `phone`, `email`, `status`, `dept_id`, `role_ids`) VALUES
('employee', '$2a$10$8eyCA6Eqk.TFL.j5iEXd/ujAMtA6GEpOEtYupOt8OpivljDyIr.fy', '普通员工', '13800000004', 'employee@finance.com', 1, 6, '["EMPLOYEE"]')
ON DUPLICATE KEY UPDATE
    `password`   = VALUES(`password`),
    `real_name`  = VALUES(`real_name`),
    `phone`      = VALUES(`phone`),
    `email`      = VALUES(`email`),
    `status`     = VALUES(`status`),
    `dept_id`    = VALUES(`dept_id`),
    `role_ids`   = VALUES(`role_ids`),
    `update_time`= NOW();

-- ------------------------------------------------------------
-- 4. 角色代码字典说明（无需建表，代码中硬编码）：
--    ADMIN     管理员       全部菜单
--    FINANCE   财务         数据底座 + 业财核算(含模型配置) + 智能决策
--    OPERATOR  运营         数据底座(只读) + 业财核算(只读,无模型配置) + 智能决策
--    EMPLOYEE  普通员工     智能决策（驾驶舱 + AI 顾问）
-- ------------------------------------------------------------
