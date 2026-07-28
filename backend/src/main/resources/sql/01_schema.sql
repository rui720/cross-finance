-- ============================================================
-- 跨境金融业财核算与智能决策平台 - 数据库建表脚本
-- 数据库：MySQL 8.0+  字符集：utf8mb4
-- 创建日期：2026-07-14
-- ============================================================

-- 强制客户端连接字符集为 utf8mb4，避免 Windows 命令行默认 GBK 导致中文 COMMENT 写入乱码
SET NAMES utf8mb4;

-- 先删旧库，确保字符集干净重建（如库内有数据请先备份）
DROP DATABASE IF EXISTS `cross_finance`;

CREATE DATABASE `cross_finance`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_general_ci;

USE `cross_finance`;

-- 删除顺序：先删有外键依赖的表
DROP TABLE IF EXISTS `profit_report`;
DROP TABLE IF EXISTS `exchange_rate_snapshot`;
DROP TABLE IF EXISTS `extra_cost`;
DROP TABLE IF EXISTS `import_batch`;
DROP TABLE IF EXISTS `import_template`;
DROP TABLE IF EXISTS `raw_order`;
DROP TABLE IF EXISTS `sys_audit_log`;
DROP TABLE IF EXISTS `sys_user`;

-- ------------------------------------------------------------
-- 1. 系统用户表
-- ------------------------------------------------------------
CREATE TABLE `sys_user` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `username`     VARCHAR(64)  NOT NULL COMMENT '用户名（登录凭据，不作为业务唯一约束，删除后可复用）',
    `employee_no`  VARCHAR(32)  NOT NULL COMMENT '员工工号（业务唯一标识，EMP+年份+顺序号，deleted=0 范围内唯一）',
    `password`     VARCHAR(128) NOT NULL COMMENT '密码（BCrypt 加密）',
    `real_name`    VARCHAR(64)  DEFAULT NULL COMMENT '真实姓名',
    `phone`        VARCHAR(20)  DEFAULT NULL COMMENT '手机号',
    `email`        VARCHAR(128) DEFAULT NULL COMMENT '邮箱',
    `status`       TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0 禁用，1 启用',
    `dept_id`      BIGINT       DEFAULT NULL COMMENT '部门 ID',
    `role_ids`     VARCHAR(256) DEFAULT NULL COMMENT '角色 ID 列表（JSON 字符串）',
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `create_by`    BIGINT       DEFAULT NULL COMMENT '创建人 ID',
    `update_by`    BIGINT       DEFAULT NULL COMMENT '更新人 ID',
    `deleted`      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_username` (`username`),
    KEY `idx_employee_no` (`employee_no`),
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
    `operation`   VARCHAR(256) DEFAULT NULL COMMENT '操作描述（中文+类名.方法名）',
    `old_value`   TEXT         DEFAULT NULL COMMENT '操作前数据快照（JSON，用于撤销恢复与详情展示）',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0 失败，1 成功',
    `undone`      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否已撤销：0 未撤销，1 已撤销',
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
    `id`               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `order_no`         VARCHAR(64)   NOT NULL COMMENT '订单号',
    `platform`         VARCHAR(64)   DEFAULT NULL COMMENT '平台',
    `shop_id`          VARCHAR(64)   DEFAULT NULL COMMENT '店铺 ID',
    `currency`         VARCHAR(8)    NOT NULL DEFAULT 'CNY' COMMENT '币种',
    `amount`           DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '金额',
    `fee`              DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '平台费',
    `settle_amount`    DECIMAL(18,4) DEFAULT NULL COMMENT '结算金额（折算后）',
    `order_time`       DATETIME      DEFAULT NULL COMMENT '下单时间',
    `settle_time`      DATETIME      DEFAULT NULL COMMENT '结算时间',
    `source`           VARCHAR(16)   NOT NULL DEFAULT 'PLATFORM' COMMENT '数据来源：PLATFORM/BANK/MANUAL',
    `batch_no`         VARCHAR(64)   DEFAULT NULL COMMENT '导入批次号',
    `clean_status`      TINYINT       NOT NULL DEFAULT 0 COMMENT '清洗状态：0 未清洗，1 已清洗，2 清洗失败',
    `clean_errors`      TEXT          DEFAULT NULL COMMENT '清洗错误信息（行级，JSON）',
    `clean_time`        DATETIME      DEFAULT NULL COMMENT '清洗完成时间',
    `reconcile_status`  TINYINT       NOT NULL DEFAULT 0 COMMENT '对账状态：0 未对账，1 已完成，2 对账失败，3 未到账，4 不明入账',
    `reconcile_match_id` BIGINT       DEFAULT NULL COMMENT '匹配的对方记录ID',
    `reconcile_diff`    DECIMAL(18,4) DEFAULT NULL COMMENT '对账差值（平台应收-银行到账，CNY）',
    `create_time`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `create_by`         BIGINT        DEFAULT NULL COMMENT '创建人 ID',
    `update_by`         BIGINT        DEFAULT NULL COMMENT '更新人 ID',
    `deleted`           TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_order_no` (`order_no`),
    KEY `idx_batch_no` (`batch_no`),
    KEY `idx_source` (`source`),
    KEY `idx_order_time` (`order_time`),
    KEY `idx_clean_status` (`clean_status`),
    KEY `idx_reconcile_status` (`reconcile_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='原始业务订单表（平台账单 + 银行流水统一存储）';

-- ------------------------------------------------------------
-- 3.1 导入模板配置表（字段映射 + 清洗规则，可由 AI 自动识别或人工维护）
-- ------------------------------------------------------------
CREATE TABLE `import_template` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `template_name`  VARCHAR(128) NOT NULL COMMENT '模板名称',
    `platform`       VARCHAR(64)  DEFAULT NULL COMMENT '适配平台（NULL 表示通用）',
    `source_type`    VARCHAR(16)  NOT NULL DEFAULT 'PLATFORM' COMMENT '数据来源：PLATFORM/BANK/MANUAL',
    `file_type`      VARCHAR(16)  DEFAULT NULL COMMENT '文件类型：EXCEL/CSV',
    `column_mapping` TEXT         DEFAULT NULL COMMENT '字段映射 JSON：{"orderNo":"订单号","amount":"金额",...}',
    `clean_rules`    VARCHAR(512) DEFAULT NULL COMMENT '清洗规则 Bean 名列表，逗号分隔',
    `header_row`     INT          NOT NULL DEFAULT 1 COMMENT '表头所在行号（从 1 开始）',
    `sheet_no`       INT          NOT NULL DEFAULT 0 COMMENT '解析的 sheet 索引（0 开始）',
    `ai_generated`   TINYINT      NOT NULL DEFAULT 0 COMMENT '是否 AI 自动识别生成：0 否，1 是',
    `remark`         VARCHAR(512) DEFAULT NULL COMMENT '备注',
    `status`         TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0 禁用，1 启用',
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `create_by`      BIGINT       DEFAULT NULL COMMENT '创建人 ID',
    `update_by`      BIGINT       DEFAULT NULL COMMENT '更新人 ID',
    `deleted`        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_source_type` (`source_type`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='导入模板配置表';

-- ------------------------------------------------------------
-- 3.2 导入批次状态机表（每次导入生成一条，跟踪状态流转与统计）
-- ------------------------------------------------------------
CREATE TABLE `import_batch` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `batch_no`      VARCHAR(64)  NOT NULL COMMENT '批次号（UUID）',
    `template_id`   BIGINT       DEFAULT NULL COMMENT '使用的导入模板 ID',
    `file_name`     VARCHAR(256) DEFAULT NULL COMMENT '上传文件名',
    `source_type`   VARCHAR(16)  NOT NULL DEFAULT 'PLATFORM' COMMENT '数据来源：PLATFORM/BANK/COST/MANUAL',
    `status`        VARCHAR(16)  NOT NULL DEFAULT 'IMPORTED' COMMENT '批次状态：IMPORTED/CLEANING/CLEANED/FAILED',
    `total_count`   INT          NOT NULL DEFAULT 0 COMMENT '总记录数',
    `success_count` INT          NOT NULL DEFAULT 0 COMMENT '清洗成功数',
    `failed_count`  INT          NOT NULL DEFAULT 0 COMMENT '清洗失败数',
    `error_detail`  TEXT         DEFAULT NULL COMMENT '错误明细（JSON）',
    `clean_summary` TEXT         DEFAULT NULL COMMENT '清洗结果汇总（JSON，记录各规则动作）',
    `error_msg`     VARCHAR(1024) DEFAULT NULL COMMENT '批次级错误信息',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `create_by`     BIGINT       DEFAULT NULL COMMENT '创建人 ID',
    `update_by`     BIGINT       DEFAULT NULL COMMENT '更新人 ID',
    `deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，1 已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_batch_no` (`batch_no`),
    KEY `idx_status` (`status`),
    KEY `idx_source_type` (`source_type`),
    KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='导入批次状态机表';

-- ------------------------------------------------------------
-- 3.3 额外费用表（物流/广告/仓储/关税等，参与利润核算）
-- ------------------------------------------------------------
CREATE TABLE `extra_cost` (
    `id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `cost_type`     VARCHAR(32)   NOT NULL COMMENT '费用类型：LOGISTICS/WAREHOUSE/ADVERTISING/CUSTOMS_DUTY/COMMISSION/FX_LOSS/RETURN_LOSS/TRANSACTION_FEE/PACKAGING/OTHER',
    `amount`        DECIMAL(18,4) NOT NULL COMMENT '原始金额',
    `currency`      VARCHAR(8)    NOT NULL DEFAULT 'CNY' COMMENT '币种',
    `cny_amount`    DECIMAL(18,4) DEFAULT NULL COMMENT '人民币金额（折算后，由后端回填）',
    `period`        VARCHAR(8)    NOT NULL COMMENT '核算周期，如 202607',
    `order_no`      VARCHAR(64)   DEFAULT NULL COMMENT '关联订单号（空则进入公共成本池分摊）',
    `payee`         VARCHAR(128)  DEFAULT NULL COMMENT '收款方',
    `cost_date`     DATE          NOT NULL COMMENT '费用发生日期',
    `remark`        VARCHAR(512)  DEFAULT NULL COMMENT '备注',
    `source`        VARCHAR(16)   NOT NULL DEFAULT 'IMPORT' COMMENT '来源：IMPORT/MANUAL',
    `batch_no`      VARCHAR(64)   DEFAULT NULL COMMENT '导入批次号',
    `status`        TINYINT       NOT NULL DEFAULT 1 COMMENT '状态：0 已作废，1 生效',
    `create_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `create_by`     BIGINT        DEFAULT NULL COMMENT '创建人 ID',
    `update_by`     BIGINT        DEFAULT NULL COMMENT '更新人 ID',
    `deleted`       TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_period` (`period`),
    KEY `idx_cost_type` (`cost_type`),
    KEY `idx_order_no` (`order_no`),
    KEY `idx_batch_no` (`batch_no`),
    KEY `idx_cost_date` (`cost_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='额外费用表（物流/广告/仓储等）';

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
-- 5. 利润报表表
-- ------------------------------------------------------------
CREATE TABLE `profit_report` (
    `id`              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `period`          VARCHAR(32)   NOT NULL COMMENT '核算周期：月份模式如 202607，范围模式如 2026-08-15~2026-09-15',
    `order_no`        VARCHAR(64)   NOT NULL COMMENT '订单号',
    `platform`        VARCHAR(64)   DEFAULT NULL COMMENT '平台',
    `shop_id`         VARCHAR(64)   DEFAULT NULL COMMENT '店铺 ID',
    `currency`        VARCHAR(8)    NOT NULL DEFAULT 'CNY' COMMENT '币种',
    `original_amount` DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '原始金额',
    `cny_amount`      DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '人民币金额（折算后）',
    `fee_cost`        DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '平台费（该订单自身的平台费，直接归属）',
    `shared_cost`     DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '公共分摊成本（无订单号的额外费用按策略分摊）',
    `direct_cost`     DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '直接成本（按订单号归集的额外费用）',
    `cost_amount`     DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '总成本 = fee_cost + shared_cost + direct_cost',
    `profit_amount`   DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '利润',
    `profit_rate`     DECIMAL(10,4) NOT NULL DEFAULT 0.0000 COMMENT '利润率',
    `order_time`      DATETIME      DEFAULT NULL COMMENT '订单时间（用于日/周趋势聚合）',
    `reconcile_status` TINYINT      NOT NULL DEFAULT 0 COMMENT '对账状态：0 未对账，1 已完成，2 对账失败，3 未到账，4 不明入账',
    `actual_received_amount` DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '实际到账金额（CNY，未到账为 0）',
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
    KEY `idx_shop_id` (`shop_id`),
    KEY `idx_order_time` (`order_time`),
    KEY `idx_reconcile_status` (`reconcile_status`),
    KEY `idx_rule_id` (`rule_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='利润报表表';
