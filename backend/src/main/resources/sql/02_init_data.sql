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
(2, 'finance','$2a$10$Gv/mqE11mEnTYcnOfPedKOviLA4bEAwxNQ/9vJGjfxnD6O6dtOdXe', '财务专员',   '13800000001', 'finance@finance.com', 1, 2, '["FINANCE"]'),
(3, 'approver','$2a$10$03s3gZusZHz3bFmuxop0MO.Qj1r31Jk.XD7CUnbjsqDnvNrA56bxO','审批经理',  '13800000002', 'approver@finance.com',1, 3, '["APPROVER"]');

-- ------------------------------------------------------------
-- 2. 费用分摊规则
-- ------------------------------------------------------------
INSERT INTO `cost_allocation_rule` (`id`, `rule_name`, `rule_type`, `enabled`, `description`, `formula`) VALUES
(1, '按金额分摊（默认）', 'AMOUNT', 1, '按订单金额占比分摊公共成本池', '{"basis":"amount","precision":4}'),
(2, '按重量分摊',         'WEIGHT', 0, '按订单数量占比分摊物流成本池', '{"basis":"quantity","precision":4}');

-- ------------------------------------------------------------
-- 3. 汇率快照（以 CNY 为目标币种，即 1 单位外币 = rate 人民币）
-- ------------------------------------------------------------
INSERT INTO `exchange_rate_snapshot` (`rate_date`, `from_currency`, `to_currency`, `rate`, `source`) VALUES
(CURDATE(), 'USD', 'CNY', 7.25000000,  '央行'),
(CURDATE(), 'EUR', 'CNY', 7.85000000,  '央行'),
(CURDATE(), 'HKD', 'CNY', 0.92800000,  '央行'),
(CURDATE(), 'JPY', 'CNY', 0.04560000,  '央行'),
(CURDATE(), 'CNY', 'CNY', 1.00000000,  '系统');

-- ------------------------------------------------------------
-- 4. 预算计划
-- ------------------------------------------------------------
INSERT INTO `budget_plan` (`id`, `plan_name`, `period`, `total_amount`, `used_amount`, `currency`, `dept_id`, `warning_threshold`) VALUES
(1, '2026年7月运营预算', '202607', 1000000.0000, 320000.0000, 'CNY', 1, 80.00),
(2, '2026年7月物流预算', '202607', 500000.0000,  410000.0000, 'CNY', 2, 80.00),
(3, '2026年7月营销预算', '202607', 800000.0000,  150000.0000, 'CNY', 3, 75.00);

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

-- ------------------------------------------------------------
-- 6. 示例付款申请单
-- ------------------------------------------------------------
INSERT INTO `payment_apply` (`apply_no`, `payee`, `bank_account`, `currency`, `amount`, `apply_reason`, `status`, `applicant_id`, `apply_time`, `budget_plan_id`) VALUES
('PAY-20260714-0001', '深圳跨境物流有限公司', '622202******890123', 'CNY', 50000.0000, '7月份跨境物流尾款', 1, 2, '2026-07-10 09:00:00', 2),
('PAY-20260714-0002', '上海广告传媒有限公司', '622202******210987', 'CNY', 80000.0000, '7月份平台广告投放费', 1, 2, '2026-07-12 14:30:00', 3),
('PAY-20260714-0003', '广州仓储服务有限公司', '622202******443332', 'CNY', 30000.0000, '7月份仓储租金', 2, 2, '2026-07-08 10:15:00', 1);
