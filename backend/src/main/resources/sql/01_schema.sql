-- ============================================================
-- 跨境金融业财核算与智能决策平台 - 数据库建表脚本
-- 数据库：MySQL 8.0+  字符集：utf8mb4
-- 创建日期：2026-07-14
-- ============================================================

-- 先删旧库，确保字符集干净重建（如库内有数据请先备份）
DROP DATABASE IF EXISTS `cross_finance`;

CREATE DATABASE `cross_finance`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_general_ci;

USE `cross_finance`;

-- 删除顺序：先删有外键依赖的表
DROP TABLE IF EXISTS `profit_report`;
DROP TABLE IF EXISTS `payment_apply`;
DROP TABLE IF EXISTS `budget_plan`;
DROP TABLE IF EXISTS `cost_allocation_rule`;
DROP TABLE IF EXISTS `exchange_rate_snapshot`;
DROP TABLE IF EXISTS `raw_order`;
DROP TABLE IF EXISTS `sys_audit_log`;
DROP TABLE IF EXISTS `sys_user`;

-- ------------------------------------------------------------
-- 1. 系统用户表
-- ------------------------------------------------------------
CREATE TABLE `sys_user` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `username`    VARCHAR(64)  NOT NULL COMMENT '用户名',
    `password`    VARCHAR(128) NOT NULL COMMENT '密码（BCrypt 加密）',
    `real_name`   VARCHAR(64)  DEFAULT NULL COMMENT '真实姓名',
    `phone`       VARCHAR(20)  DEFAULT NULL COMMENT '手机号',
    `email`       VARCHAR(128) DEFAULT NULL COMMENT '邮箱',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0 禁用，1 启用',
    `dept_id`     BIGINT       DEFAULT NULL COMMENT '部门 ID',
    `role_ids`    VARCHAR(256) DEFAULT NULL COMMENT '角色 ID 列表（JSON 字符串）',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `create_by`   BIGINT       DEFAULT NULL COMMENT '创建人 ID',
    `update_by`   BIGINT       DEFAULT NULL COMMENT '更新人 ID',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，1 已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    KEY `idx_dept_id` (`dept_id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户表';

-- ------------------------------------------------------------
-- 2. 系统审计日志表
-- ------------------------------------------------------------
CREATE TABLE `sys_audit_log` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `user_id`     BIGINT       DEFAULT NULL COMMENT '操作用户 ID',
    `username`    VARCHAR(64)  DEFAULT NULL COMMENT '操作用户名',
    `operation`   VARCHAR(256) DEFAULT NULL COMMENT '操作描述',
    `method`      VARCHAR(256) DEFAULT NULL COMMENT '请求方法（类#方法）',
    `params`      TEXT         DEFAULT NULL COMMENT '请求参数（JSON）',
    `ip`          VARCHAR(64)  DEFAULT NULL COMMENT '请求 IP',
    `cost_time`   BIGINT       DEFAULT NULL COMMENT '耗时（ms）',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0 失败，1 成功',
    `error_msg`   TEXT         DEFAULT NULL COMMENT '错误信息',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `create_by`   BIGINT       DEFAULT NULL COMMENT '创建人 ID',
    `update_by`   BIGINT       DEFAULT NULL COMMENT '更新人 ID',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统审计日志表';

-- ------------------------------------------------------------
-- 3. 原始业务订单表（平台账单 + 银行流水统一存储）
-- ------------------------------------------------------------
CREATE TABLE `raw_order` (
    `id`             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `order_no`       VARCHAR(64)   NOT NULL COMMENT '订单号',
    `platform`       VARCHAR(64)   DEFAULT NULL COMMENT '平台',
    `shop_id`        VARCHAR(64)   DEFAULT NULL COMMENT '店铺 ID',
    `currency`       VARCHAR(8)    NOT NULL DEFAULT 'CNY' COMMENT '币种',
    `amount`         DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '金额',
    `fee`            DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '平台费',
    `settle_amount`  DECIMAL(18,4) DEFAULT NULL COMMENT '结算金额（折算后）',
    `order_time`     DATETIME      DEFAULT NULL COMMENT '下单时间',
    `settle_time`    DATETIME      DEFAULT NULL COMMENT '结算时间',
    `source`         VARCHAR(16)   NOT NULL DEFAULT 'PLATFORM' COMMENT '数据来源：PLATFORM/BANK/MANUAL',
    `batch_no`       VARCHAR(64)   DEFAULT NULL COMMENT '导入批次号',
    `create_time`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `create_by`      BIGINT        DEFAULT NULL COMMENT '创建人 ID',
    `update_by`      BIGINT        DEFAULT NULL COMMENT '更新人 ID',
    `deleted`        TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_order_no` (`order_no`),
    KEY `idx_batch_no` (`batch_no`),
    KEY `idx_source` (`source`),
    KEY `idx_order_time` (`order_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='原始业务订单表';

-- ------------------------------------------------------------
-- 4. 汇率快照表
-- ------------------------------------------------------------
CREATE TABLE `exchange_rate_snapshot` (
    `id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `rate_date`     DATE          NOT NULL COMMENT '汇率日期',
    `from_currency` VARCHAR(8)    NOT NULL COMMENT '源币种',
    `to_currency`   VARCHAR(8)    NOT NULL COMMENT '目标币种',
    `rate`          DECIMAL(18,8) NOT NULL COMMENT '汇率',
    `source`        VARCHAR(32)   DEFAULT NULL COMMENT '来源：央行/第三方',
    `create_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `create_by`     BIGINT        DEFAULT NULL COMMENT '创建人 ID',
    `update_by`     BIGINT        DEFAULT NULL COMMENT '更新人 ID',
    `deleted`       TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，1 已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_date_pair` (`rate_date`, `from_currency`, `to_currency`),
    KEY `idx_from_currency` (`from_currency`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='汇率快照表';

-- ------------------------------------------------------------
-- 5. 费用分摊规则配置表
-- ------------------------------------------------------------
CREATE TABLE `cost_allocation_rule` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `rule_name`   VARCHAR(128) NOT NULL COMMENT '规则名',
    `rule_type`   VARCHAR(16)  NOT NULL COMMENT '类型：WEIGHT/AMOUNT',
    `enabled`     TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用：0 禁用，1 启用',
    `description` VARCHAR(512) DEFAULT NULL COMMENT '描述',
    `formula`     TEXT         DEFAULT NULL COMMENT '分摊公式（JSON）',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `create_by`   BIGINT       DEFAULT NULL COMMENT '创建人 ID',
    `update_by`   BIGINT       DEFAULT NULL COMMENT '更新人 ID',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_rule_type` (`rule_type`),
    KEY `idx_enabled` (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='费用分摊规则配置表';

-- ------------------------------------------------------------
-- 6. 利润报表表
-- ------------------------------------------------------------
CREATE TABLE `profit_report` (
    `id`              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `period`          VARCHAR(8)    NOT NULL COMMENT '核算周期，如 202607',
    `order_no`        VARCHAR(64)   NOT NULL COMMENT '订单号',
    `platform`        VARCHAR(64)   DEFAULT NULL COMMENT '平台',
    `currency`        VARCHAR(8)    NOT NULL DEFAULT 'CNY' COMMENT '币种',
    `original_amount` DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '原始金额',
    `cny_amount`      DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '人民币金额（折算后）',
    `cost_amount`     DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '分摊成本',
    `profit_amount`   DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '利润',
    `profit_rate`     DECIMAL(10,4) NOT NULL DEFAULT 0.0000 COMMENT '利润率',
    `rule_id`         BIGINT        DEFAULT NULL COMMENT '分摊规则 ID',
    `create_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `create_by`       BIGINT        DEFAULT NULL COMMENT '创建人 ID',
    `update_by`       BIGINT        DEFAULT NULL COMMENT '更新人 ID',
    `deleted`         TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_period` (`period`),
    KEY `idx_order_no` (`order_no`),
    KEY `idx_platform` (`platform`),
    KEY `idx_rule_id` (`rule_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='利润报表表';

-- ------------------------------------------------------------
-- 7. 预算计划表
-- ------------------------------------------------------------
CREATE TABLE `budget_plan` (
    `id`                BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `plan_name`         VARCHAR(128)  NOT NULL COMMENT '计划名',
    `period`            VARCHAR(8)    NOT NULL COMMENT '预算周期',
    `total_amount`      DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '预算总额',
    `used_amount`       DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '已使用金额',
    `currency`          VARCHAR(8)    NOT NULL DEFAULT 'CNY' COMMENT '币种',
    `dept_id`           BIGINT        DEFAULT NULL COMMENT '部门 ID',
    `warning_threshold` DECIMAL(5,2)  NOT NULL DEFAULT 80.00 COMMENT '预警阈值百分比，如 80 表示 80%',
    `create_time`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `create_by`         BIGINT        DEFAULT NULL COMMENT '创建人 ID',
    `update_by`         BIGINT        DEFAULT NULL COMMENT '更新人 ID',
    `deleted`           TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_period` (`period`),
    KEY `idx_dept_id` (`dept_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预算计划表';

-- ------------------------------------------------------------
-- 8. 付款申请单表
-- ------------------------------------------------------------
CREATE TABLE `payment_apply` (
    `id`             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `apply_no`       VARCHAR(64)   NOT NULL COMMENT '申请单号',
    `payee`          VARCHAR(128)  NOT NULL COMMENT '收款方',
    `bank_account`   VARCHAR(64)   DEFAULT NULL COMMENT '收款账号',
    `currency`       VARCHAR(8)    NOT NULL DEFAULT 'CNY' COMMENT '币种',
    `amount`         DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '金额',
    `apply_reason`   VARCHAR(512)  DEFAULT NULL COMMENT '付款事由',
    `status`         TINYINT       NOT NULL DEFAULT 0 COMMENT '状态：0草稿 1待审批 2已通过 3已驳回 4已付款',
    `applicant_id`   BIGINT        DEFAULT NULL COMMENT '申请人 ID',
    `apply_time`     DATETIME      DEFAULT NULL COMMENT '申请时间',
    `budget_plan_id` BIGINT        DEFAULT NULL COMMENT '关联预算 ID',
    `create_time`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `create_by`      BIGINT        DEFAULT NULL COMMENT '创建人 ID',
    `update_by`      BIGINT        DEFAULT NULL COMMENT '更新人 ID',
    `deleted`        TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，1 已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_apply_no` (`apply_no`),
    KEY `idx_status` (`status`),
    KEY `idx_applicant_id` (`applicant_id`),
    KEY `idx_budget_plan_id` (`budget_plan_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='付款申请单表';
