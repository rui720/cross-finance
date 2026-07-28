package com.finance.platform.data.etl.rule;

import com.finance.platform.data.entity.RawOrder;
import org.springframework.stereotype.Component;

/**
 * 去重规则：基于 platform + order_no 复合键去重
 * <p>
 * 同一批次内重复的订单号标记为清洗失败。
 */
@Component
public class DeduplicateRule implements CleanRule {

    @Override
    public String ruleName() {
        return "deduplicateRule";
    }

    @Override
    public CleanResult apply(RawOrder order, CleanContext context) {
        String key = CleanContext.dedupKey(order.getPlatform(), order.getOrderNo());
        if (context.getSeenOrderKeys().contains(key)) {
            context.addAction(ruleName(), "订单 " + order.getOrderNo() + " 重复，已跳过");
            return CleanResult.fail("重复订单号：" + order.getOrderNo());
        }
        context.getSeenOrderKeys().add(key);
        return CleanResult.pass();
    }
}
