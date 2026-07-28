-- 10_template_default_flag.sql
-- 模板默认常量机制：
-- 1. import_template 表新增 is_default 字段，标记默认模板（不可删除）
-- 2. 现有名称含「默认」的模板标记为 is_default=1
-- 3. 银行流水默认模板的 column_mapping 添加 platform（银行）字段映射
SET NAMES utf8mb4;

-- 1. 新增 is_default 列
ALTER TABLE `import_template`
    ADD COLUMN `is_default` TINYINT NOT NULL DEFAULT 0
    COMMENT '是否默认模板（常量）：0 否，1 是。默认模板不可删除，用户删除自定义模板后默认模板自动生效'
    AFTER `status`;

-- 2. 标记现有默认模板
UPDATE `import_template`
SET `is_default` = 1
WHERE `template_name` LIKE '%默认%'
   OR `template_name` LIKE '%通用%';

-- 3. 更新银行流水默认模板：column_mapping 添加 platform（银行）字段映射
--    使银行流水导入时能记录是哪家银行的入账
UPDATE `import_template`
SET `column_mapping` = '{"orderNo":"交易流水号","platform":"银行","amount":"交易金额","currency":"币种","orderTime":"交易时间","settleTime":"入账时间"}'
WHERE `source_type` = 'BANK'
  AND `is_default` = 1;

-- 4. 验证
SELECT id, template_name, source_type, is_default, status FROM `import_template` ORDER BY `source_type`, `id`;
