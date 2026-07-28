package com.finance.platform.data.service;

import com.finance.platform.data.entity.RawOrder;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 数据 ETL 服务接口
 * <p>
 * 账单文件导入、数据清洗、银行流水对账等数据底座核心能力。
 * <p>
 * 重构后：导入支持模板配置（templateId），清洗基于规则引擎责任链。
 */
public interface DataEtlService {

    /**
     * 账单导入结果
     *
     * @param batchNo            批次号
     * @param totalRows          文件解析出的总行数
     * @param successCount       成功入库条数
     * @param failedCount        失败条数（含重复跳过 + 字段映射错误等）
     * @param duplicateCount     跨批次重复跳过条数
     * @param wholeTableDuplicate 是否整表重复（所有有效订单号均已存在，未导入任何新数据）
     */
    record BillImportResult(
            String batchNo,
            int totalRows,
            int successCount,
            int failedCount,
            int duplicateCount,
            boolean wholeTableDuplicate
    ) {}

    /**
     * 导入账单文件，解析入库，返回导入结果（含重复检测信息）
     *
     * @param file       上传的文件
     * @param source     数据来源
     * @param templateId 导入模板 ID（可空，空则取默认模板）
     * @return 导入结果
     */
    BillImportResult importBill(MultipartFile file, String source, Long templateId);

    /**
     * 按批次号清洗数据：基于模板配置的规则链执行清洗
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
