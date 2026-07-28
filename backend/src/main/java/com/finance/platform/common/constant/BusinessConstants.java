package com.finance.platform.common.constant;

/**
 * 业务常量：币种、审批状态、单据类型等
 */
public final class BusinessConstants {

    private BusinessConstants() {}

    /* ============ 币种 ============ */
    public static final String CURRENCY_CNY = "CNY";
    public static final String CURRENCY_USD = "USD";
    public static final String CURRENCY_EUR = "EUR";
    public static final String CURRENCY_HKD = "HKD";
    public static final String CURRENCY_JPY = "JPY";

    /* ============ 单据类型 ============ */
    public static final String DOC_BANK_FLOW = "BANK_FLOW";
    public static final String DOC_RAW_ORDER = "RAW_ORDER";

    /* ============ 数据来源 ============ */
    public static final String SOURCE_PLATFORM = "PLATFORM";
    public static final String SOURCE_BANK = "BANK";
    public static final String SOURCE_MANUAL = "MANUAL";
    /** 额外费用导入（物流/广告/仓储等） */
    public static final String SOURCE_COST = "COST";
    /** 历史汇率批量导入 */
    public static final String SOURCE_EXCHANGE_RATE = "EXCHANGE_RATE";
}
