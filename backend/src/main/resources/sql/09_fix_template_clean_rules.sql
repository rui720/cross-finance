-- 09_fix_template_clean_rules.sql
-- 修复导入模板的清洗规则配置：
-- 原配置引用了不存在的规则 Bean（nullCheckRule/numberFormatRule/dateFormatRule），
-- 且缺少关键的 currencyConvertRule（币种换算），导致清洗后 settleAmount 始终为空。
-- 现有规则 Bean：trimRule, defaultCurrencyRule, filterInvalidRule, currencyConvertRule, deduplicateRule
SET NAMES utf8mb4;

-- 平台账单模板：完整清洗链（含币种换算和去重）
UPDATE `import_template`
SET `clean_rules` = 'trimRule,defaultCurrencyRule,filterInvalidRule,currencyConvertRule,deduplicateRule'
WHERE `source_type` = 'PLATFORM' AND `template_name` LIKE '%默认%';

-- 银行流水模板：完整清洗链（含币种换算和去重）
UPDATE `import_template`
SET `clean_rules` = 'trimRule,defaultCurrencyRule,filterInvalidRule,currencyConvertRule,deduplicateRule'
WHERE `source_type` = 'BANK' AND `template_name` LIKE '%默认%';

-- 额外费用模板：不需要币种换算和去重
UPDATE `import_template`
SET `clean_rules` = 'trimRule,defaultCurrencyRule,filterInvalidRule'
WHERE `source_type` = 'COST' AND `template_name` LIKE '%默认%';

-- 兜底：如果还有其他模板引用了不存在的规则，统一修复为有效规则链
UPDATE `import_template`
SET `clean_rules` = 'trimRule,defaultCurrencyRule,filterInvalidRule,currencyConvertRule,deduplicateRule'
WHERE `clean_rules` LIKE '%nullCheckRule%'
   OR `clean_rules` LIKE '%numberFormatRule%'
   OR `clean_rules` LIKE '%dateFormatRule%';
