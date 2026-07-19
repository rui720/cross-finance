package com.finance.platform.data.task;

import com.finance.platform.data.service.ExchangeRateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每日汇率同步定时任务
 * <p>
 * 每天 9:00 执行，拉取最新汇率并刷新内存换算表。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExchangeRateSyncTask {

    private final ExchangeRateService exchangeRateService;

    /**
     * 每天 9:00 同步汇率
     */
    @Scheduled(cron = "0 0 9 * * ?")
    public void syncExchangeRate() {
        log.info("[ExchangeRateSync] 开始同步汇率");
        try {
            // TODO: 调用第三方汇率 API（央行/开放接口）拉取当日汇率，调用 exchangeRateService.saveSnapshot 落库
            exchangeRateService.refreshCache();
            log.info("[ExchangeRateSync] 汇率同步完成");
        } catch (Exception e) {
            log.error("[ExchangeRateSync] 汇率同步失败", e);
        }
    }
}
