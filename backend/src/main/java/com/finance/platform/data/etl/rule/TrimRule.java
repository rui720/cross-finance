package com.finance.platform.data.etl.rule;

import com.finance.platform.data.entity.RawOrder;
import org.springframework.stereotype.Component;

/**
 * 字段标准化规则：trim 空白、币种大写、平台名归一
 */
@Component
public class TrimRule implements CleanRule {

    @Override
    public String ruleName() {
        return "trimRule";
    }

    @Override
    public CleanResult apply(RawOrder order, CleanContext context) {
        if (order.getOrderNo() != null) order.setOrderNo(order.getOrderNo().trim());
        if (order.getPlatform() != null) {
            String p = order.getPlatform().trim();
            // 平台名归一：大小写不敏感
            if ("amazon".equalsIgnoreCase(p)) p = "Amazon";
            else if ("shopee".equalsIgnoreCase(p)) p = "Shopee";
            else if ("ebay".equalsIgnoreCase(p)) p = "eBay";
            else if ("rakuten".equalsIgnoreCase(p)) p = "Rakuten";
            order.setPlatform(p);
        }
        if (order.getShopId() != null) order.setShopId(order.getShopId().trim());
        if (order.getCurrency() != null) order.setCurrency(order.getCurrency().trim().toUpperCase());
        return CleanResult.pass();
    }
}
