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

    /* ============ 审批状态 ============ */
    public static final int APPROVAL_DRAFT = 0;       // 草稿
    public static final int APPROVAL_PENDING = 1;     // 待审批
    public static final int APPROVAL_APPROVED = 2;    // 已通过
    public static final int APPROVAL_REJECTED = 3;    // 已驳回
    public static final int APPROVAL_PAID = 4;        // 已付款

    /* ============ 单据类型 ============ */
    public static final String DOC_PAYMENT_APPLY = "PAYMENT_APPLY";
    public static final String DOC_BANK_FLOW = "BANK_FLOW";
    public static final String DOC_RAW_ORDER = "RAW_ORDER";

    /* ============ 数据来源 ============ */
    public static final String SOURCE_PLATFORM = "PLATFORM";
    public static final String SOURCE_BANK = "BANK";
    public static final String SOURCE_MANUAL = "MANUAL";
}
