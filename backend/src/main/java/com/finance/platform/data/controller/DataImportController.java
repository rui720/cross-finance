package com.finance.platform.data.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finance.platform.common.constant.BusinessConstants;
import com.finance.platform.common.core.Result;
import com.finance.platform.data.mapper.RawOrderMapper;
import com.finance.platform.data.service.DataEtlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 账单/银行流水文件导入接口
 * <p>
 * 权限：查询批次记录对 ADMIN/FINANCE/OPERATOR 开放；
 * 上传文件、清洗数据等写操作仅 ADMIN/FINANCE 可用（运营只读）。
 */
@Slf4j
@RestController
@RequestMapping("/data/import")
@RequiredArgsConstructor
public class DataImportController {

    private final DataEtlService dataEtlService;
    private final RawOrderMapper rawOrderMapper;

    /**
     * 分页查询导入批次记录（按 batch_no 分组）
     *
     * @param page 当前页
     * @param size 每页条数
     */
    @GetMapping("/bill/page")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','OPERATOR')")
    public Result<IPage<Map<String, Object>>> billPage(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        Page<Map<String, Object>> p = new Page<>(page, size);
        IPage<Map<String, Object>> result = rawOrderMapper.selectBatchPage(p);
        return Result.success(result);
    }

    /**
     * 上传平台账单文件
     *
     * @param file   Excel 文件
     * @param source 数据来源
     * @return 批次号
     */
    @PostMapping("/bill")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public Result<String> importBill(@RequestParam("file") MultipartFile file,
                                     @RequestParam(defaultValue = BusinessConstants.SOURCE_PLATFORM) String source) {
        String batchNo = dataEtlService.importBill(file, source);
        return Result.success(batchNo);
    }

    /**
     * 上传银行流水文件
     *
     * @param file Excel 文件
     * @return 批次号
     */
    @PostMapping("/bank")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public Result<String> importBankFlow(@RequestParam("file") MultipartFile file) {
        String batchNo = dataEtlService.importBill(file, BusinessConstants.SOURCE_BANK);
        return Result.success(batchNo);
    }

    /**
     * 按批次号清洗数据：去重、币种换算、字段标准化
     *
     * @param batchNo 批次号
     */
    @PostMapping("/clean")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public Result<Void> clean(@RequestParam String batchNo) {
        log.info("[ETL] 触发数据清洗 batchNo={}", batchNo);
        dataEtlService.cleanData(batchNo);
        return Result.success();
    }
}
