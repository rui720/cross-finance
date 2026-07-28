package com.finance.platform.data.etl.rule;

import com.finance.platform.common.constant.BusinessConstants;
import com.finance.platform.data.entity.RawOrder;
import org.springframework.stereotype.Component;

/**
 * 缺省币种规则：币种为空时按 CNY 处理
 */
@Component
public class DefaultCurrencyRule implements CleanRule {

    @Override
    public String ruleName() {
        return "defaultCurrencyRule";
    }

    @Override
    public CleanResult apply(RawOrder order, CleanContext context) {
        if (order.getCurrency() == null || order.getCurrency().isBlank()) {
            order.setCurrency(BusinessConstants.CURRENCY_CNY);
            context.addAction(ruleName(), "订单 " + order.getOrderNo() + " 币种为空，已默认按 CNY 处理");
        }
        return CleanResult.pass();
    }
}
