-- ============================================================
-- 跨境金融平台 - 初始化数据脚本
-- 执行前请先执行 01_schema.sql 建表
-- ============================================================

-- 强制客户端连接字符集为 utf8mb4，避免 Windows 命令行默认 GBK 导致中文写入失败
SET NAMES utf8mb4;

USE `cross_finance`;

-- ------------------------------------------------------------
-- 1. 系统用户（BCrypt 加密存储的初始密码，首次登录后请强制修改）
--    哈希值由 BCryptPasswordEncoder 生成，如需重置请用 passwordEncoder.encode("新密码") 重新生成
-- ------------------------------------------------------------
INSERT INTO `sys_user` (`id`, `username`, `password`, `real_name`, `phone`, `email`, `status`, `dept_id`, `role_ids`) VALUES
(1, 'admin',  '$2a$10$8eyCA6Eqk.TFL.j5iEXd/ujAMtA6GEpOEtYupOt8OpivljDyIr.fy', '系统管理员', '13800000000', 'admin@finance.com', 1, 1, '["ADMIN"]'),
(2, 'finance','$2a$10$Gv/mqE11mEnTYcnOfPedKOviLA4bEAwxNQ/9vJGjfxnD6O6dtOdXe', '财务专员',   '13800000001', 'finance@finance.com', 1, 2, '["FINANCE"]');

-- ------------------------------------------------------------
-- 1.1 导入模板（方案 B+D 默认模板，平台账单 + 银行流水 + 额外费用）
-- ------------------------------------------------------------
INSERT INTO `import_template` (`template_name`, `platform`, `source_type`, `file_type`, `column_mapping`, `clean_rules`, `header_row`, `sheet_no`, `ai_generated`, `remark`, `status`) VALUES
('平台账单默认模板', NULL, 'PLATFORM', 'EXCEL',
 '{"orderNo":"订单号","platform":"平台","shopId":"店铺ID","currency":"币种","amount":"金额","fee":"手续费","settleAmount":"结算金额","orderTime":"下单时间","settleTime":"结算时间"}',
 'trimRule,defaultCurrencyRule,filterInvalidRule,currencyConvertRule,deduplicateRule',
 1, 0, 0, '系统默认平台账单导入模板', 1),
('银行流水默认模板', NULL, 'BANK', 'EXCEL',
 '{"orderNo":"交易流水号","amount":"交易金额","currency":"币种","orderTime":"交易时间","settleTime":"入账时间"}',
 'trimRule,defaultCurrencyRule,filterInvalidRule,currencyConvertRule,deduplicateRule',
 1, 0, 0, '系统默认银行流水导入模板', 1),
('额外费用默认模板', NULL, 'COST', 'EXCEL',
 '{"costType":"费用类型","amount":"金额","currency":"币种","period":"核算周期","orderNo":"订单号","payee":"收款方","costDate":"费用日期","remark":"备注"}',
 'trimRule,defaultCurrencyRule,filterInvalidRule',
 1, 0, 0, '系统默认额外费用导入模板', 1);

-- ------------------------------------------------------------
-- 2. 汇率快照（以 CNY 为目标币种，即 1 单位外币 = rate 人民币）
-- ------------------------------------------------------------
INSERT INTO `exchange_rate_snapshot` (`rate_date`, `from_currency`, `to_currency`, `rate`, `source`) VALUES
(CURDATE(), 'USD', 'CNY', 7.25000000,  '央行'),
(CURDATE(), 'EUR', 'CNY', 7.85000000,  '央行'),
(CURDATE(), 'HKD', 'CNY', 0.92800000,  '央行'),
(CURDATE(), 'JPY', 'CNY', 0.04560000,  '央行'),
(CURDATE(), 'CNY', 'CNY', 1.00000000,  '系统');

-- ------------------------------------------------------------
-- 5. 示例原始订单（用于快速验证利润核算流程）
-- ------------------------------------------------------------
INSERT INTO `raw_order` (`order_no`, `platform`, `shop_id`, `currency`, `amount`, `fee`, `settle_amount`, `order_time`, `settle_time`, `source`, `batch_no`) VALUES
('ORD-202607-0001', 'Amazon', 'SHOP001', 'USD', 1500.0000, 150.0000, NULL, '2026-07-01 10:00:00', '2026-07-03 10:00:00', 'PLATFORM', 'DEMO-001'),
('ORD-202607-0002', 'Amazon', 'SHOP001', 'USD', 2200.0000, 220.0000, NULL, '2026-07-02 11:00:00', '2026-07-04 11:00:00', 'PLATFORM', 'DEMO-001'),
('ORD-202607-0003', 'Shopee', 'SHOP002', 'HKD', 8800.0000, 528.0000, NULL, '2026-07-03 09:30:00', '2026-07-05 09:30:00', 'PLATFORM', 'DEMO-001'),
('ORD-202607-0004', 'eBay',   'SHOP003', 'EUR', 980.0000,  88.2000,  NULL, '2026-07-04 14:20:00', '2026-07-06 14:20:00', 'PLATFORM', 'DEMO-001'),
('ORD-202607-0005', 'Rakuten','SHOP004', 'JPY', 56000.0000,1680.0000,NULL, '2026-07-05 16:45:00', '2026-07-07 16:45:00', 'PLATFORM', 'DEMO-001'),
('ORD-202607-0006', 'Amazon', 'SHOP001', 'USD', 3200.0000, 320.0000, NULL, '2026-07-06 08:10:00', '2026-07-08 08:10:00', 'PLATFORM', 'DEMO-001');
