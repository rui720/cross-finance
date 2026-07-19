package com.finance.platform.data.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.finance.platform.data.entity.ExchangeRateSnapshot;

import java.math.BigDecimal;

/**
 * 汇率服务接口
 * <p>
 * 提供汇率查询、快照保存及内存缓存刷新能力。
 */
public interface ExchangeRateService extends IService<ExchangeRateSnapshot> {

    /**
     * 获取最新汇率（先查缓存，未命中查库）
     *
     * @param from 源币种
     * @param to   目标币种
     * @return 汇率
     */
    BigDecimal getLatestRate(String from, String to);

    /**
     * 保存汇率快照
     *
     * @param snapshot 汇率快照
     */
    void saveSnapshot(ExchangeRateSnapshot snapshot);

    /**
     * 刷新内存汇率缓存
     */
    void refreshCache();
}
