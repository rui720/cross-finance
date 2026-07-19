package com.finance.platform.common.utils;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * EasyExcel 账单解析工具
 */
@Slf4j
@Component
public class ExcelParseUtils {

    /**
     * 解析 Excel 文件并批量处理
     *
     * @param file 上传的 Excel 文件
     * @param headClazz 表头映射类
     * @param consumer 每批数据处理回调（按 batchSize 切分）
     * @param batchSize 批大小
     */
    public <T> void parse(MultipartFile file, Class<T> headClazz, Consumer<List<T>> consumer, int batchSize) {
        try (InputStream in = file.getInputStream()) {
            List<T> buffer = new ArrayList<>(batchSize);
            EasyExcel.read(in, headClazz, new ReadListener<T>() {
                @Override
                public void invoke(T data, AnalysisContext context) {
                    buffer.add(data);
                    if (buffer.size() >= batchSize) {
                        consumer.accept(new ArrayList<>(buffer));
                        buffer.clear();
                    }
                }

                @Override
                public void doAfterAllAnalysed(AnalysisContext context) {
                    if (!buffer.isEmpty()) {
                        consumer.accept(new ArrayList<>(buffer));
                        buffer.clear();
                    }
                }
            }).sheet().doRead();
            log.info("[ExcelParse] 文件 {} 解析完成", file.getOriginalFilename());
        } catch (IOException e) {
            log.error("[ExcelParse] 解析失败 file={}", file.getOriginalFilename(), e);
            throw new RuntimeException("Excel 解析失败：" + e.getMessage(), e);
        }
    }

    /**
     * 解析全部数据到内存（小文件场景）
     */
    public <T> List<T> parseAll(MultipartFile file, Class<T> headClazz) {
        List<T> result = new ArrayList<>();
        try (InputStream in = file.getInputStream()) {
            EasyExcel.read(in, headClazz, new ReadListener<T>() {
                @Override
                public void invoke(T data, AnalysisContext context) {
                    result.add(data);
                }

                @Override
                public void doAfterAllAnalysed(AnalysisContext context) {
                    // no-op
                }
            }).sheet().doRead();
        } catch (IOException e) {
            log.error("[ExcelParse] 解析失败 file={}", file.getOriginalFilename(), e);
            throw new RuntimeException("Excel 解析失败：" + e.getMessage(), e);
        }
        return result;
    }
}
