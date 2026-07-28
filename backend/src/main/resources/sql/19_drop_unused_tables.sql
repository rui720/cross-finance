-- ============================================================
-- 删除已废弃但未清理的残留表
-- 创建日期：2026-07-27
-- 变更内容：
--   1. payment_apply        付款申请表（资金风控模块已整体移除，无对应实体/Mapper/Controller）
--   2. budget_plan          预算计划表（规划阶段建表，从未实现业务逻辑，无对应实体/Mapper/Controller）
--   3. cost_allocation_rule 成本分摊规则表（核算引擎改为硬编码按金额占比分摊，配置层已移除）
-- ============================================================

SET NAMES utf8mb4;
USE `cross_finance`;

-- 付款申请表：原资金风控审批流的核心表，模块整体移除后未清理
DROP TABLE IF EXISTS `payment_apply`;

-- 预算计划表：仅建表未实现业务，从未在任何 Java 代码中引用
DROP TABLE IF EXISTS `budget_plan`;

-- 成本分摊规则表：ProfitEngineServiceImpl 注释明确说明"配置层已移除"
-- 核算引擎改为硬编码按金额占比分摊，rule_id 字段在 profit_report 中保留但不再写入
DROP TABLE IF EXISTS `cost_allocation_rule`;

-- 验证：剩余表清单（应为 11 张：sys_user/sys_dept/sys_audit_log/raw_order/import_template/import_batch/extra_cost/exchange_rate_snapshot/profit_report/ai_session/ai_message）
SHOW TABLES;
