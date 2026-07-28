package com.finance.platform.data.etl.rule;

import com.finance.platform.data.entity.RawOrder;

/**
 * 清洗规则策略接口
 * <p>
 * 每个规则实现单一职责：去重 / 字段标准化 / 异常过滤 / 币种换算 / ...
 * 规则按顺序组成责任链，逐条应用于 RawOrder。
 * <p>
 * 设计目标：
 * 1. 规则可配置（ImportTemplate.clean_rules 指定启用哪些规则）
 * 2. 规则可扩展（新增规则只需实现本接口 + @Component 注册）
 * 3. 规则可独立测试
 */
public interface CleanRule {

    /**
     * 规则 Bean 名（用于 ImportTemplate.clean_rules 配置引用）
     */
    String ruleName();

    /**
     * 应用规则到单条记录
     *
     * @param order   待清洗的订单（可被修改）
     * @param context 清洗上下文（提供批次号、已有订单号集合等共享状态）
     * @return 清洗结果：OK 表示通过；FAIL 表示失败（reason 填写失败原因）
     */
    CleanResult apply(RawOrder order, CleanContext context);

    /**
     * 清洗结果
     * <p>
     * 注意：record 组件 {@code ok} 会自动生成同名 accessor {@code boolean ok()}，
     * 因此静态工厂方法命名为 {@code pass()} / {@code fail()} 避免与 accessor 签名冲突。
     */
    record CleanResult(boolean ok, String reason) {
        public static CleanResult pass() { return new CleanResult(true, null); }
        public static CleanResult fail(String reason) { return new CleanResult(false, reason); }
    }
}
