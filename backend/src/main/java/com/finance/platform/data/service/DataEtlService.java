package com.finance.platform.data.service;

import com.finance.platform.data.entity.RawOrder;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 数据 ETL 服务接口
 * <p>
 * 账单文件导入、数据清洗、银行流水对账等数据底座核心能力。
 */
public interface DataEtlService {

    /**
     * 导入账单文件，异步解析入库，返回批次号
     *
     * @param file   上传的 Excel 文件
     * @param source 数据来源
     * @return 批次号
     */
    String importBill(MultipartFile file, String source);

    /**
     * 按批次号清洗数据：去重、币种换算、字段标准化
     *
     * @param batchNo 批次号
     */
    void cleanData(String batchNo);

    /**
     * 银行流水对账
     *
     * @param batchNo 批次号
     */
    void reconcileBankFlow(String batchNo);

    /**
     * 异步分批入库（走 etlExecutor 线程池）
     *
     * @param orders  订单列表
     * @param batchNo 批次号
     * @param source  数据来源
     */
    void asyncSaveBatch(List<RawOrder> orders, String batchNo, String source);
}
