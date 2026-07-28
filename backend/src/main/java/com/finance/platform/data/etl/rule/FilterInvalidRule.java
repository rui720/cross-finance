package com.finance.platform.data.etl.rule;

import com.finance.platform.data.entity.RawOrder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 异常数据过滤规则：拦截金额为空/负数、订单号为空、时间倒挂等异常数据
 */
@Component
public class FilterInvalidRule implements CleanRule {

    @Override
    public String ruleName() {
        return "filterInvalidRule";
    }

    @Override
    public CleanResult apply(RawOrder order, CleanContext context) {
        if (order.getOrderNo() == null || order.getOrderNo().isBlank()) {
            context.addAction(ruleName(), "订单 " + order.getOrderNo() + " 订单号为空，已拦截");
            return CleanResult.fail("订单号为空");
        }
        if (order.getAmount() == null || order.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            context.addAction(ruleName(), "订单 " + order.getOrderNo() + " 金额为空或非正数，已拦截");
            return CleanResult.fail("金额为空或非正数");
        }
        // 时间倒挂：下单时间晚于结算时间（两者都存在时才校验）
        if (order.getOrderTime() != null && order.getSettleTime() != null
                && order.getOrderTime().isAfter(order.getSettleTime())) {
            context.addAction(ruleName(), "订单 " + order.getOrderNo() + " 下单时间晚于结算时间，已拦截");
            return CleanResult.fail("下单时间晚于结算时间");
        }
        return CleanResult.pass();
    }
}
