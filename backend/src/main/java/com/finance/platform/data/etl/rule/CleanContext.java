package com.finance.platform.data.etl.rule;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 清洗上下文
 * <p>
 * 在一次批次清洗中跨规则共享的状态，如已见过的订单号集合（用于去重）。
 * 同时收集各规则的清洗动作（异常拦截/缺省补全/汇率折算/去重），用于前端展示清洗结果。
 * 格式统一（TrimRule）不记录动作。
 */
@Data
public class CleanContext {

    /** 批次号 */
    private final String batchNo;

    /** 已见过的订单号集合（order_no + platform 复合键），用于去重规则 */
    private final Set<String> seenOrderKeys = new HashSet<>();

    /** 清洗动作列表（按规则分组汇总后存入 import_batch.clean_summary） */
    private final List<CleanAction> actions = new ArrayList<>();

    public CleanContext(String batchNo) {
        this.batchNo = batchNo;
    }

    /** 生成去重键：platform + "|" + orderNo */
    public static String dedupKey(String platform, String orderNo) {
        return (platform == null ? "" : platform) + "|" + (orderNo == null ? "" : orderNo);
    }

    /** 记录一条清洗动作 */
    public void addAction(String ruleName, String description) {
        actions.add(new CleanAction(ruleName, description));
    }

    /**
     * 清洗动作记录
     *
     * @param ruleName    规则名称（与 CleanRule.ruleName() 一致）
     * @param description 动作描述（如"3 条记录币种为空，已默认按 CNY 处理"）
     */
    public record CleanAction(String ruleName, String description) {}
}

