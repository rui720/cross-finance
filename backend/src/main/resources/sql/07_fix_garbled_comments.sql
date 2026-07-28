-- ============================================================
-- 修复数据库表/字段中文注释乱码
-- ============================================================
-- 问题原因：早期执行 01_schema.sql 时未加 SET NAMES utf8mb4;，
--           Windows mysql 命令行默认 GBK 解码 UTF-8 脚本，
--           导致 COMMENT 中的中文被错误存储为乱码（如"主键 ID"→"涓婚敭 ID"）。
-- 修复方法：用正确的 UTF-8 中文重新设置所有表和字段的 COMMENT。
--
-- 执行方式（PowerShell）：
--   Get-Content -Path "07_fix_garbled_comments.sql" -Encoding UTF8 -Raw | mysql -u root -p
-- 或（CMD）：
--   mysql -u root -p --default-character-set=utf8mb4 < 07_fix_garbled_comments.sql
--
-- 注意：必须用 --default-character-set=utf8mb4 或 SET NAMES utf8mb4; 保证客户端连接字符集正确
-- ============================================================

SET NAMES utf8mb4;
USE `cross_finance`;

-- ============================================================
-- 1. sys_user（系统用户表）
-- ============================================================
ALTER TABLE `sys_user` COMMENT='系统用户表';
ALTER TABLE `sys_user` MODIFY COLUMN `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键 ID';
ALTER TABLE `sys_user` MODIFY COLUMN `username`    VARCHAR(64)  NOT NULL COMMENT '用户名';
ALTER TABLE `sys_user` MODIFY COLUMN `password`    VARCHAR(128) NOT NULL COMMENT '密码（BCrypt 加密）';
ALTER TABLE `sys_user` MODIFY COLUMN `real_name`   VARCHAR(64)  DEFAULT NULL COMMENT '真实姓名';
ALTER TABLE `sys_user` MODIFY COLUMN `phone`       VARCHAR(20)  DEFAULT NULL COMMENT '手机号';
ALTER TABLE `sys_user` MODIFY COLUMN `email`       VARCHAR(128) DEFAULT NULL COMMENT '邮箱';
ALTER TABLE `sys_user` MODIFY COLUMN `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0 禁用，1 启用';
ALTER TABLE `sys_user` MODIFY COLUMN `dept_id`     BIGINT       DEFAULT NULL COMMENT '部门 ID';
ALTER TABLE `sys_user` MODIFY COLUMN `role_ids`    VARCHAR(256) DEFAULT NULL COMMENT '角色 ID 列表（JSON 字符串）';
ALTER TABLE `sys_user` MODIFY COLUMN `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';
ALTER TABLE `sys_user` MODIFY COLUMN `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';
ALTER TABLE `sys_user` MODIFY COLUMN `create_by`   BIGINT       DEFAULT NULL COMMENT '创建人 ID';
ALTER TABLE `sys_user` MODIFY COLUMN `update_by`   BIGINT       DEFAULT NULL COMMENT '更新人 ID';
ALTER TABLE `sys_user` MODIFY COLUMN `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，1 已删除';

-- ============================================================
-- 2. sys_audit_log（系统审计日志表）
-- ============================================================
ALTER TABLE `sys_audit_log` COMMENT='系统审计日志表';
ALTER TABLE `sys_audit_log` MODIFY COLUMN `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键 ID';
ALTER TABLE `sys_audit_log` MODIFY COLUMN `user_id`     BIGINT       DEFAULT NULL COMMENT '操作用户 ID';
ALTER TABLE `sys_audit_log` MODIFY COLUMN `username`    VARCHAR(64)  DEFAULT NULL COMMENT '操作用户名';
ALTER TABLE `sys_audit_log` MODIFY COLUMN `operation`   VARCHAR(256) DEFAULT NULL COMMENT '操作描述';
ALTER TABLE `sys_audit_log` MODIFY COLUMN `method`      VARCHAR(256) DEFAULT NULL COMMENT '请求方法（类#方法）';
ALTER TABLE `sys_audit_log` MODIFY COLUMN `params`      TEXT         DEFAULT NULL COMMENT '请求参数（JSON）';
ALTER TABLE `sys_audit_log` MODIFY COLUMN `ip`          VARCHAR(64)  DEFAULT NULL COMMENT '请求 IP';
ALTER TABLE `sys_audit_log` MODIFY COLUMN `cost_time`   BIGINT       DEFAULT NULL COMMENT '耗时（ms）';
ALTER TABLE `sys_audit_log` MODIFY COLUMN `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0 失败，1 成功';
ALTER TABLE `sys_audit_log` MODIFY COLUMN `error_msg`   TEXT         DEFAULT NULL COMMENT '错误信息';
ALTER TABLE `sys_audit_log` MODIFY COLUMN `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';
ALTER TABLE `sys_audit_log` MODIFY COLUMN `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';
ALTER TABLE `sys_audit_log` MODIFY COLUMN `create_by`   BIGINT       DEFAULT NULL COMMENT '创建人 ID';
ALTER TABLE `sys_audit_log` MODIFY COLUMN `update_by`   BIGINT       DEFAULT NULL COMMENT '更新人 ID';
ALTER TABLE `sys_audit_log` MODIFY COLUMN `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，1 已删除';

-- ============================================================
-- 3. raw_order（原始业务订单表）
-- ============================================================
ALTER TABLE `raw_order` COMMENT='原始业务订单表（平台账单 + 银行流水统一存储）';
ALTER TABLE `raw_order` MODIFY COLUMN `id`               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键 ID';
ALTER TABLE `raw_order` MODIFY COLUMN `order_no`         VARCHAR(64)   NOT NULL COMMENT '订单号';
ALTER TABLE `raw_order` MODIFY COLUMN `platform`         VARCHAR(64)   DEFAULT NULL COMMENT '平台';
ALTER TABLE `raw_order` MODIFY COLUMN `shop_id`          VARCHAR(64)   DEFAULT NULL COMMENT '店铺 ID';
ALTER TABLE `raw_order` MODIFY COLUMN `currency`         VARCHAR(8)    NOT NULL DEFAULT 'CNY' COMMENT '币种';
ALTER TABLE `raw_order` MODIFY COLUMN `amount`           DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '金额';
ALTER TABLE `raw_order` MODIFY COLUMN `fee`              DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '平台费';
ALTER TABLE `raw_order` MODIFY COLUMN `settle_amount`    DECIMAL(18,4) DEFAULT NULL COMMENT '结算金额（折算后）';
ALTER TABLE `raw_order` MODIFY COLUMN `order_time`       DATETIME      DEFAULT NULL COMMENT '下单时间';
ALTER TABLE `raw_order` MODIFY COLUMN `settle_time`      DATETIME      DEFAULT NULL COMMENT '结算时间';
ALTER TABLE `raw_order` MODIFY COLUMN `source`           VARCHAR(16)   NOT NULL DEFAULT 'PLATFORM' COMMENT '数据来源：PLATFORM/BANK/MANUAL';
ALTER TABLE `raw_order` MODIFY COLUMN `batch_no`         VARCHAR(64)   DEFAULT NULL COMMENT '导入批次号';
ALTER TABLE `raw_order` MODIFY COLUMN `clean_status`     TINYINT       NOT NULL DEFAULT 0 COMMENT '清洗状态：0 未清洗，1 已清洗，2 清洗失败';
ALTER TABLE `raw_order` MODIFY COLUMN `clean_errors`     TEXT          DEFAULT NULL COMMENT '清洗错误信息（行级，JSON）';
ALTER TABLE `raw_order` MODIFY COLUMN `clean_time`       DATETIME      DEFAULT NULL COMMENT '清洗完成时间';
ALTER TABLE `raw_order` MODIFY COLUMN `reconcile_status` TINYINT       NOT NULL DEFAULT 0 COMMENT '对账状态：0 未对账，1 已对账，2 差异';
ALTER TABLE `raw_order` MODIFY COLUMN `create_time`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';
ALTER TABLE `raw_order` MODIFY COLUMN `update_time`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';
ALTER TABLE `raw_order` MODIFY COLUMN `create_by`        BIGINT        DEFAULT NULL COMMENT '创建人 ID';
ALTER TABLE `raw_order` MODIFY COLUMN `update_by`        BIGINT        DEFAULT NULL COMMENT '更新人 ID';
ALTER TABLE `raw_order` MODIFY COLUMN `deleted`          TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，1 已删除';

-- ============================================================
-- 4. exchange_rate_snapshot（汇率快照表）
-- ============================================================
ALTER TABLE `exchange_rate_snapshot` COMMENT='汇率快照表';
ALTER TABLE `exchange_rate_snapshot` MODIFY COLUMN `id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键 ID';
ALTER TABLE `exchange_rate_snapshot` MODIFY COLUMN `rate_date`     DATE          NOT NULL COMMENT '汇率日期';
ALTER TABLE `exchange_rate_snapshot` MODIFY COLUMN `from_currency` VARCHAR(8)    NOT NULL COMMENT '源币种';
ALTER TABLE `exchange_rate_snapshot` MODIFY COLUMN `to_currency`   VARCHAR(8)    NOT NULL COMMENT '目标币种';
ALTER TABLE `exchange_rate_snapshot` MODIFY COLUMN `rate`          DECIMAL(18,8) NOT NULL COMMENT '汇率';
ALTER TABLE `exchange_rate_snapshot` MODIFY COLUMN `source`        VARCHAR(32)   DEFAULT NULL COMMENT '来源：央行/第三方';
ALTER TABLE `exchange_rate_snapshot` MODIFY COLUMN `create_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';
ALTER TABLE `exchange_rate_snapshot` MODIFY COLUMN `update_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';
ALTER TABLE `exchange_rate_snapshot` MODIFY COLUMN `create_by`     BIGINT        DEFAULT NULL COMMENT '创建人 ID';
ALTER TABLE `exchange_rate_snapshot` MODIFY COLUMN `update_by`     BIGINT        DEFAULT NULL COMMENT '更新人 ID';
ALTER TABLE `exchange_rate_snapshot` MODIFY COLUMN `deleted`       TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，1 已删除';

-- ============================================================
-- 5. cost_allocation_rule（费用分摊规则配置表）
-- ============================================================
ALTER TABLE `cost_allocation_rule` COMMENT='费用分摊规则配置表';
ALTER TABLE `cost_allocation_rule` MODIFY COLUMN `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键 ID';
ALTER TABLE `cost_allocation_rule` MODIFY COLUMN `rule_name`   VARCHAR(128) NOT NULL COMMENT '规则名';
ALTER TABLE `cost_allocation_rule` MODIFY COLUMN `rule_type`   VARCHAR(16)  NOT NULL COMMENT '类型：WEIGHT/AMOUNT';
ALTER TABLE `cost_allocation_rule` MODIFY COLUMN `enabled`     TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用：0 禁用，1 启用';
ALTER TABLE `cost_allocation_rule` MODIFY COLUMN `description` VARCHAR(512) DEFAULT NULL COMMENT '描述';
ALTER TABLE `cost_allocation_rule` MODIFY COLUMN `formula`     TEXT         DEFAULT NULL COMMENT '分摊公式（JSON）';
ALTER TABLE `cost_allocation_rule` MODIFY COLUMN `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';
ALTER TABLE `cost_allocation_rule` MODIFY COLUMN `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';
ALTER TABLE `cost_allocation_rule` MODIFY COLUMN `create_by`   BIGINT       DEFAULT NULL COMMENT '创建人 ID';
ALTER TABLE `cost_allocation_rule` MODIFY COLUMN `update_by`   BIGINT       DEFAULT NULL COMMENT '更新人 ID';
ALTER TABLE `cost_allocation_rule` MODIFY COLUMN `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，1 已删除';

-- ============================================================
-- 6. profit_report（利润报表表）
-- ============================================================
ALTER TABLE `profit_report` COMMENT='利润报表表';
ALTER TABLE `profit_report` MODIFY COLUMN `id`              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键 ID';
ALTER TABLE `profit_report` MODIFY COLUMN `period`          VARCHAR(8)    NOT NULL COMMENT '核算周期，如 202607';
ALTER TABLE `profit_report` MODIFY COLUMN `order_no`        VARCHAR(64)   NOT NULL COMMENT '订单号';
ALTER TABLE `profit_report` MODIFY COLUMN `platform`        VARCHAR(64)   DEFAULT NULL COMMENT '平台';
ALTER TABLE `profit_report` MODIFY COLUMN `currency`        VARCHAR(8)    NOT NULL DEFAULT 'CNY' COMMENT '币种';
ALTER TABLE `profit_report` MODIFY COLUMN `original_amount` DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '原始金额';
ALTER TABLE `profit_report` MODIFY COLUMN `cny_amount`      DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '人民币金额（折算后）';
ALTER TABLE `profit_report` MODIFY COLUMN `cost_amount`     DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '分摊成本';
ALTER TABLE `profit_report` MODIFY COLUMN `profit_amount`   DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '利润';
ALTER TABLE `profit_report` MODIFY COLUMN `profit_rate`     DECIMAL(10,4) NOT NULL DEFAULT 0.0000 COMMENT '利润率';
ALTER TABLE `profit_report` MODIFY COLUMN `rule_id`         BIGINT        DEFAULT NULL COMMENT '分摊规则 ID';
ALTER TABLE `profit_report` MODIFY COLUMN `create_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';
ALTER TABLE `profit_report` MODIFY COLUMN `update_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';
ALTER TABLE `profit_report` MODIFY COLUMN `create_by`       BIGINT        DEFAULT NULL COMMENT '创建人 ID';
ALTER TABLE `profit_report` MODIFY COLUMN `update_by`       BIGINT        DEFAULT NULL COMMENT '更新人 ID';
ALTER TABLE `profit_report` MODIFY COLUMN `deleted`         TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，1 已删除';

-- ============================================================
-- 9. import_template（导入模板配置表）
-- ============================================================
ALTER TABLE `import_template` COMMENT='导入模板配置表';
ALTER TABLE `import_template` MODIFY COLUMN `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键 ID';
ALTER TABLE `import_template` MODIFY COLUMN `template_name`  VARCHAR(128) NOT NULL COMMENT '模板名称';
ALTER TABLE `import_template` MODIFY COLUMN `platform`       VARCHAR(64)  DEFAULT NULL COMMENT '适配平台（NULL 表示通用）';
ALTER TABLE `import_template` MODIFY COLUMN `source_type`    VARCHAR(16)  NOT NULL DEFAULT 'PLATFORM' COMMENT '数据来源：PLATFORM/BANK/MANUAL';
ALTER TABLE `import_template` MODIFY COLUMN `file_type`      VARCHAR(16)  DEFAULT NULL COMMENT '文件类型：EXCEL/CSV';
ALTER TABLE `import_template` MODIFY COLUMN `column_mapping` TEXT         DEFAULT NULL COMMENT '字段映射 JSON：{"orderNo":"订单号","amount":"金额",...}';
ALTER TABLE `import_template` MODIFY COLUMN `clean_rules`    VARCHAR(512) DEFAULT NULL COMMENT '清洗规则 Bean 名列表，逗号分隔';
ALTER TABLE `import_template` MODIFY COLUMN `header_row`     INT          NOT NULL DEFAULT 1 COMMENT '表头所在行号（从 1 开始）';
ALTER TABLE `import_template` MODIFY COLUMN `sheet_no`       INT          NOT NULL DEFAULT 0 COMMENT '解析的 sheet 索引（0 开始）';
ALTER TABLE `import_template` MODIFY COLUMN `ai_generated`   TINYINT      NOT NULL DEFAULT 0 COMMENT '是否 AI 自动识别生成：0 否，1 是';
ALTER TABLE `import_template` MODIFY COLUMN `remark`         VARCHAR(512) DEFAULT NULL COMMENT '备注';
ALTER TABLE `import_template` MODIFY COLUMN `status`         TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0 禁用，1 启用';
ALTER TABLE `import_template` MODIFY COLUMN `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';
ALTER TABLE `import_template` MODIFY COLUMN `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';
ALTER TABLE `import_template` MODIFY COLUMN `create_by`      BIGINT       DEFAULT NULL COMMENT '创建人 ID';
ALTER TABLE `import_template` MODIFY COLUMN `update_by`      BIGINT       DEFAULT NULL COMMENT '更新人 ID';
ALTER TABLE `import_template` MODIFY COLUMN `deleted`        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，1 已删除';

-- ============================================================
-- 10. import_batch（导入批次状态机表）
-- ============================================================
ALTER TABLE `import_batch` COMMENT='导入批次状态机表';
ALTER TABLE `import_batch` MODIFY COLUMN `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键 ID';
ALTER TABLE `import_batch` MODIFY COLUMN `batch_no`      VARCHAR(64)  NOT NULL COMMENT '批次号（UUID）';
ALTER TABLE `import_batch` MODIFY COLUMN `template_id`   BIGINT       DEFAULT NULL COMMENT '使用的导入模板 ID';
ALTER TABLE `import_batch` MODIFY COLUMN `file_name`     VARCHAR(256) DEFAULT NULL COMMENT '上传文件名';
ALTER TABLE `import_batch` MODIFY COLUMN `source_type`   VARCHAR(16)  NOT NULL DEFAULT 'PLATFORM' COMMENT '数据来源：PLATFORM/BANK/COST/MANUAL';
ALTER TABLE `import_batch` MODIFY COLUMN `status`        VARCHAR(16)  NOT NULL DEFAULT 'IMPORTED' COMMENT '批次状态：IMPORTED/CLEANING/CLEANED/FAILED';
ALTER TABLE `import_batch` MODIFY COLUMN `total_count`   INT          NOT NULL DEFAULT 0 COMMENT '总记录数';
ALTER TABLE `import_batch` MODIFY COLUMN `success_count` INT          NOT NULL DEFAULT 0 COMMENT '清洗成功数';
ALTER TABLE `import_batch` MODIFY COLUMN `failed_count`  INT          NOT NULL DEFAULT 0 COMMENT '清洗失败数';
ALTER TABLE `import_batch` MODIFY COLUMN `error_detail`  TEXT         DEFAULT NULL COMMENT '错误明细（JSON）';
ALTER TABLE `import_batch` MODIFY COLUMN `error_msg`     VARCHAR(1024) DEFAULT NULL COMMENT '批次级错误信息';
ALTER TABLE `import_batch` MODIFY COLUMN `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';
ALTER TABLE `import_batch` MODIFY COLUMN `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';
ALTER TABLE `import_batch` MODIFY COLUMN `create_by`     BIGINT       DEFAULT NULL COMMENT '创建人 ID';
ALTER TABLE `import_batch` MODIFY COLUMN `update_by`     BIGINT       DEFAULT NULL COMMENT '更新人 ID';
ALTER TABLE `import_batch` MODIFY COLUMN `deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，1 已删除';

-- ============================================================
-- 11. extra_cost（额外费用表）
-- ============================================================
ALTER TABLE `extra_cost` COMMENT='额外费用表（物流/广告/仓储等）';
ALTER TABLE `extra_cost` MODIFY COLUMN `id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键 ID';
ALTER TABLE `extra_cost` MODIFY COLUMN `cost_type`     VARCHAR(32)   NOT NULL COMMENT '费用类型：LOGISTICS/WAREHOUSE/ADVERTISING/CUSTOMS_DUTY/COMMISSION/FX_LOSS/RETURN_LOSS/TRANSACTION_FEE/PACKAGING/OTHER';
ALTER TABLE `extra_cost` MODIFY COLUMN `amount`        DECIMAL(18,4) NOT NULL COMMENT '原始金额';
ALTER TABLE `extra_cost` MODIFY COLUMN `currency`      VARCHAR(8)    NOT NULL DEFAULT 'CNY' COMMENT '币种';
ALTER TABLE `extra_cost` MODIFY COLUMN `cny_amount`    DECIMAL(18,4) DEFAULT NULL COMMENT '人民币金额（折算后，由后端回填）';
ALTER TABLE `extra_cost` MODIFY COLUMN `period`        VARCHAR(8)    NOT NULL COMMENT '核算周期，如 202607';
ALTER TABLE `extra_cost` MODIFY COLUMN `order_no`      VARCHAR(64)   DEFAULT NULL COMMENT '关联订单号（空则进入公共成本池分摊）';
ALTER TABLE `extra_cost` MODIFY COLUMN `payee`         VARCHAR(128)  DEFAULT NULL COMMENT '收款方';
ALTER TABLE `extra_cost` MODIFY COLUMN `cost_date`     DATE          NOT NULL COMMENT '费用发生日期';
ALTER TABLE `extra_cost` MODIFY COLUMN `remark`        VARCHAR(512)  DEFAULT NULL COMMENT '备注';
ALTER TABLE `extra_cost` MODIFY COLUMN `source`        VARCHAR(16)   NOT NULL DEFAULT 'IMPORT' COMMENT '来源：IMPORT/MANUAL';
ALTER TABLE `extra_cost` MODIFY COLUMN `batch_no`      VARCHAR(64)   DEFAULT NULL COMMENT '导入批次号';
ALTER TABLE `extra_cost` MODIFY COLUMN `status`        TINYINT       NOT NULL DEFAULT 1 COMMENT '状态：0 已作废，1 生效';
ALTER TABLE `extra_cost` MODIFY COLUMN `create_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';
ALTER TABLE `extra_cost` MODIFY COLUMN `update_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';
ALTER TABLE `extra_cost` MODIFY COLUMN `create_by`     BIGINT        DEFAULT NULL COMMENT '创建人 ID';
ALTER TABLE `extra_cost` MODIFY COLUMN `update_by`     BIGINT        DEFAULT NULL COMMENT '更新人 ID';
ALTER TABLE `extra_cost` MODIFY COLUMN `deleted`       TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，1 已删除';

-- ============================================================
-- 12. sys_dept（部门表）—— 2026-07-14 后新增表，注释已是正确中文，跳过
-- ============================================================

-- ============================================================
-- 13. ai_session（AI 顾问会话表）—— 注释已是正确中文，跳过
-- ============================================================

-- ============================================================
-- 14. ai_message（AI 顾问消息表）—— 注释已是正确中文，跳过
-- ============================================================

-- 修复完成，可用以下 SQL 验证：
-- SELECT TABLE_NAME, TABLE_COMMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA='cross_finance';
-- SELECT TABLE_NAME, COLUMN_NAME, COLUMN_COMMENT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='cross_finance' ORDER BY TABLE_NAME, ORDINAL_POSITION;
