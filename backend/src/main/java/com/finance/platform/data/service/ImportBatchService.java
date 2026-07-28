package com.finance.platform.data.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finance.platform.data.entity.ImportBatch;
import com.finance.platform.data.mapper.ImportBatchMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 导入批次服务（状态机管理）
 * <p>
 * 维护批次状态流转：IMPORTED → CLEANING → CLEANED / FAILED。
 * <p>
 * 优化：状态变更改为单条 UPDATE SQL（按 batch_no 条件），避免先 selectOne 再 updateById 的 2 次 SQL。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportBatchService {

    private final ImportBatchMapper importBatchMapper;

    /**
     * 创建批次记录（初始状态 IMPORTED）
     */
    public ImportBatch create(String batchNo, Long templateId, String fileName, String sourceType) {
        ImportBatch batch = new ImportBatch();
        batch.setBatchNo(batchNo);
        batch.setTemplateId(templateId);
        batch.setFileName(fileName);
        batch.setSourceType(sourceType);
        batch.setStatus(ImportBatch.STATUS_IMPORTED);
        batch.setTotalCount(0);
        batch.setSuccessCount(0);
        batch.setFailedCount(0);
        importBatchMapper.insert(batch);
        return batch;
    }

    /**
     * 更新总数（单条 UPDATE SQL）
     */
    public void updateTotalCount(String batchNo, int totalCount) {
        importBatchMapper.update(null, new LambdaUpdateWrapper<ImportBatch>()
                .eq(ImportBatch::getBatchNo, batchNo)
                .set(ImportBatch::getTotalCount, totalCount));
    }

    /**
     * 标记为清洗中（单条 UPDATE SQL）
     */
    public void markCleaning(String batchNo) {
        importBatchMapper.update(null, new LambdaUpdateWrapper<ImportBatch>()
                .eq(ImportBatch::getBatchNo, batchNo)
                .set(ImportBatch::getStatus, ImportBatch.STATUS_CLEANING));
    }

    /**
     * 标记为清洗完成（单条 UPDATE SQL）
     */
    public void markCleaned(String batchNo, int successCount, int failedCount, String errorDetail) {
        markCleaned(batchNo, successCount, failedCount, errorDetail, null);
    }

    /**
     * 标记为清洗完成（含清洗汇总，单条 UPDATE SQL）
     *
     * @param cleanSummary 清洗结果汇总 JSON（记录各规则的动作：异常拦截/缺省补全/汇率折算/去重）
     */
    public void markCleaned(String batchNo, int successCount, int failedCount,
                            String errorDetail, String cleanSummary) {
        importBatchMapper.update(null, new LambdaUpdateWrapper<ImportBatch>()
                .eq(ImportBatch::getBatchNo, batchNo)
                .set(ImportBatch::getStatus, ImportBatch.STATUS_CLEANED)
                .set(ImportBatch::getSuccessCount, successCount)
                .set(ImportBatch::getFailedCount, failedCount)
                .set(ImportBatch::getErrorDetail, errorDetail)
                .set(ImportBatch::getCleanSummary, cleanSummary));
    }

    /**
     * 标记为清洗失败（单条 UPDATE SQL）
     */
    public void markFailed(String batchNo, String errorMsg) {
        importBatchMapper.update(null, new LambdaUpdateWrapper<ImportBatch>()
                .eq(ImportBatch::getBatchNo, batchNo)
                .set(ImportBatch::getStatus, ImportBatch.STATUS_FAILED)
                .set(ImportBatch::getErrorMsg, errorMsg));
    }

    /**
     * 按批次号查询
     */
    public ImportBatch getByBatchNo(String batchNo) {
        return importBatchMapper.selectOne(new LambdaQueryWrapper<ImportBatch>()
                .eq(ImportBatch::getBatchNo, batchNo));
    }

    /**
     * 更新批次记录
     */
    public void update(ImportBatch batch) {
        importBatchMapper.updateById(batch);
    }

    /**
     * 分页查询所有批次（数据库分页，避免内存分页）
     *
     * @param sourceType 数据来源筛选（PLATFORM/BANK），为空则查全部
     */
    public Page<ImportBatch> page(long page, long size, String sourceType) {
        return importBatchMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<ImportBatch>()
                        .eq(StrUtil.isNotBlank(sourceType), ImportBatch::getSourceType, sourceType)
                        .orderByDesc(ImportBatch::getCreateTime));
    }

    /**
     * 查询所有批次（保留兼容，仅在数据量小或一次性导出时使用）
     */
    public List<ImportBatch> listAll() {
        return importBatchMapper.selectList(new LambdaQueryWrapper<ImportBatch>()
                .orderByDesc(ImportBatch::getCreateTime));
    }
}
