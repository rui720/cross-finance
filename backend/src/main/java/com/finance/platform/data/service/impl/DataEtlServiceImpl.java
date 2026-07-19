package com.finance.platform.data.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.platform.common.constant.BusinessConstants;
import com.finance.platform.common.exception.BusinessException;
import com.finance.platform.common.utils.CurrencyConvertUtils;
import com.finance.platform.common.utils.ExcelParseUtils;
import com.finance.platform.data.entity.RawOrder;
import com.finance.platform.data.mapper.RawOrderMapper;
import com.finance.platform.data.service.DataEtlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据 ETL 服务实现
 * <p>
 * 账单文件解析、分批异步入库、数据清洗（去重/币种换算/标准化）、银行流水对账。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataEtlServiceImpl implements DataEtlService {

    /** 单批解析入库大小 */
    private static final int BATCH_SIZE = 500;

    private final ExcelParseUtils excelParseUtils;
    private final RawOrderMapper rawOrderMapper;
    private final CurrencyConvertUtils currencyConvertUtils;

    /**
     * 自注入代理引用，使 {@link #asyncSaveBatch} 的 @Async 生效（避免自调用失效）。
     */
    @Lazy
    @Autowired
    private DataEtlService self;

    @Override
    public String importBill(MultipartFile file, String source) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件不能为空");
        }
        String batchNo = IdUtil.simpleUUID();
        log.info("[ETL] 开始导入账单 file={}, source={}, batchNo={}", file.getOriginalFilename(), source, batchNo);
        // 流式解析：每读满一批即填充批次号与来源后异步落库
        excelParseUtils.parse(file, RawOrder.class, batch -> {
            batch.forEach(o -> {
                o.setBatchNo(batchNo);
                if (StrUtil.isBlank(o.getSource())) {
                    o.setSource(source);
                }
            });
            self.asyncSaveBatch(batch, batchNo, source);
        }, BATCH_SIZE);
        return batchNo;
    }

    @Async("etlExecutor")
    @Override
    public void asyncSaveBatch(List<RawOrder> orders, String batchNo, String source) {
        // 批量插入（MyBatis-Plus 3.5+ 静态工具，避免逐条 insert 的性能损耗）
        com.baomidou.mybatisplus.extension.toolkit.Db.saveBatch(orders);
        log.info("[ETL] 批次 {} 入库 {} 条, source={}", batchNo, orders.size(), source);
    }

    @Override
    public void cleanData(String batchNo) {
        if (StrUtil.isBlank(batchNo)) {
            throw new BusinessException("批次号不能为空");
        }
        List<RawOrder> list = rawOrderMapper.selectList(new LambdaQueryWrapper<RawOrder>()
                .eq(RawOrder::getBatchNo, batchNo));
        if (list.isEmpty()) {
            log.warn("[ETL] 清洗批次 {} 无数据", batchNo);
            return;
        }
        for (RawOrder order : list) {
            // 字段标准化：缺省币种按 CNY 处理
            if (StrUtil.isBlank(order.getCurrency())) {
                order.setCurrency(BusinessConstants.CURRENCY_CNY);
            }
            // 币种换算：非 CNY 统一折算为人民币存入结算金额
            if (!BusinessConstants.CURRENCY_CNY.equals(order.getCurrency())) {
                order.setSettleAmount(currencyConvertUtils.toCny(order.getAmount(), order.getCurrency()));
            } else {
                order.setSettleAmount(order.getAmount());
            }
            order.setSettleTime(LocalDateTime.now());
            rawOrderMapper.updateById(order);
        }
        log.info("[ETL] 批次 {} 清洗完成，共 {} 条", batchNo, list.size());
    }

    @Override
    public void reconcileBankFlow(String batchNo) {
        log.info("[ETL] 银行流水对账开始 batchNo={}", batchNo);
        List<RawOrder> bankFlows = rawOrderMapper.selectList(new LambdaQueryWrapper<RawOrder>()
                .eq(RawOrder::getBatchNo, batchNo)
                .eq(RawOrder::getSource, BusinessConstants.SOURCE_BANK));
        // TODO: 与平台订单逐笔匹配，标记对账状态（一致/差异/缺失）
        log.info("[ETL] 银行流水对账完成 batchNo={}, 记录数={}", batchNo, bankFlows.size());
    }
}
