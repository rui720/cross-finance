package com.finance.platform.common.constant;

/**
 * Redis 缓存 Key 前缀常量
 */
public final class RedisKeyConstants {

    private RedisKeyConstants() {}

    /** 登录 token：login:token:{userId} */
    public static final String LOGIN_TOKEN = "login:token:";

    /** 登录验证码：login:captcha:{key} */
    public static final String LOGIN_CAPTCHA = "login:captcha:";

    /** 用户权限：user:perm:{userId} */
    public static final String USER_PERM = "user:perm:";

    /** 汇率快照：exchange:rate:{date}:{fromCurrency}:{toCurrency} */
    public static final String EXCHANGE_RATE = "exchange:rate:";

    /** ETL 导入幂等锁：etl:lock:{batchNo} */
    public static final String ETL_LOCK = "etl:lock:";

    /** 利润报表缓存：profit:report:{period} */
    public static final String PROFIT_REPORT = "profit:report:";

    /** AI 会话上下文：ai:session:{sessionId} */
    public static final String AI_SESSION = "ai:session:";
}
