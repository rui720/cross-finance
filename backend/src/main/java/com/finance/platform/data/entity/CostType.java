package com.finance.platform.data.entity;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 额外费用类型枚举
 * <p>
 * 覆盖跨境金融业务负责人视角的 10 类主要费用，每类含中文名便于前端展示与导入校验。
 * 导入文件中"费用类型"列可填中文（如"物流费"）或英文编码（如"LOGISTICS"），
 * 解析时通过 {@link #fromText} 统一识别。
 */
public enum CostType {

    /** 物流费：头程/尾程/海外仓发货 */
    LOGISTICS("物流费"),
    /** 仓储费：仓库租金/操作费/出入库费 */
    WAREHOUSE("仓储费"),
    /** 广告费：平台广告/站外推广/KOL */
    ADVERTISING("广告费"),
    /** 关税税费：进口关税/增值税/消费税 */
    CUSTOMS_DUTY("关税税费"),
    /** 平台佣金：月结佣金/销售佣金（区别于 raw_order.fee 的单笔手续费） */
    COMMISSION("平台佣金"),
    /** 汇兑损失：汇率波动损失/结汇损失 */
    FX_LOSS("汇兑损失"),
    /** 退货损失：退款/退货/售后损失 */
    RETURN_LOSS("退货损失"),
    /** 手续费：支付通道费/跨境汇款费/提现费 */
    TRANSACTION_FEE("手续费"),
    /** 中转手续费：中转行/中间行扣收的汇款费用（需填 orderNo 关联到具体订单，对账时用于解释账单-银行差值） */
    TRANSFER_FEE("中转手续费"),
    /** 包装费：包材/包装人工 */
    PACKAGING("包装费"),
    /** 其他兜底 */
    OTHER("其他");

    private final String label;

    CostType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 中文标签 -> 枚举的反向索引（启动时构建一次） */
    private static final Map<String, CostType> LABEL_INDEX = Arrays.stream(values())
            .collect(Collectors.toMap(CostType::getLabel, Function.identity()));

    /** 名称（不区分大小写） -> 枚举的反向索引 */
    private static final Map<String, CostType> NAME_INDEX = Arrays.stream(values())
            .collect(Collectors.toMap(t -> t.name().toUpperCase(), Function.identity()));

    /**
     * 从字符串识别费用类型，兼容中文名、英文编码、英文小写：
     * <ul>
     *   <li>"物流费" → LOGISTICS</li>
     *   <li>"LOGISTICS" → LOGISTICS</li>
     *   <li>"logistics" → LOGISTICS</li>
     *   <li>未识别 → null</li>
     * </ul>
     */
    public static CostType fromText(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return null;
        // 优先匹配中文标签
        CostType byLabel = LABEL_INDEX.get(trimmed);
        if (byLabel != null) return byLabel;
        // 再匹配英文编码（大小写不敏感）
        return NAME_INDEX.get(trimmed.toUpperCase());
    }
}
