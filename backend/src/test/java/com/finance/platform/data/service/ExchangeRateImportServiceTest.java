package com.finance.platform.data.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.platform.data.entity.ExchangeRateSnapshot;
import com.finance.platform.data.etl.parser.FileParserFactory;
import com.finance.platform.data.etl.parser.ExcelFileParser;
import com.finance.platform.data.etl.parser.CsvFileParser;
import com.finance.platform.data.mapper.ExchangeRateSnapshotMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 汇率批量导入服务单元测试
 * <p>
 * 覆盖：
 * 1. CSV 文件正常导入（新增 + 更新两种场景）
 * 2. 表头自动识别（中文表头）
 * 3. 无效数据行进入错误明细
 * 4. 无法识别表头抛异常
 * 5. 导入完成后刷新内存汇率缓存
 * <p>
 * 注：H7 优化后导入逻辑改为批量入库：
 * - 一次性 selectList 查出所有已存在记录
 * - 调 exchangeRateService.saveBatch 批量插入
 * - 调 exchangeRateService.updateBatchById 批量更新
 * 测试需相应调整 mock 期望。
 */
@DisplayName("汇率批量导入服务测试")
@ExtendWith(MockitoExtension.class)
class ExchangeRateImportServiceTest {

    @Mock private ExchangeRateSnapshotMapper exchangeRateSnapshotMapper;
    @Mock private ExchangeRateService exchangeRateService;
    private final FileParserFactory fileParserFactory = new FileParserFactory(List.of(new ExcelFileParser(), new CsvFileParser()));

    @InjectMocks
    private ExchangeRateImportService service;

    @BeforeEach
    void setUp() {
        fileParserFactory.init();
        // 手动注入 fileParserFactory（@InjectMocks 不会注入非 @Mock 字段，需要反射或构造）
        try {
            java.lang.reflect.Field f = ExchangeRateImportService.class.getDeclaredField("fileParserFactory");
            f.setAccessible(true);
            f.set(service, fileParserFactory);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("CSV 导入：全部新增成功")
    void importCsvAllInserted() {
        String csv = "日期,源币种,目标币种,汇率,来源\n"
                + "2026-07-01,USD,CNY,7.25,央行\n"
                + "2026-07-02,USD,CNY,7.26,央行\n";
        MockMultipartFile file = new MockMultipartFile("file", "rates.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        // 库中查不到任何已有记录 → 全部走 saveBatch
        when(exchangeRateSnapshotMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        ExchangeRateImportService.ImportResult result = service.importRates(file);

        assertThat(result.inserted()).isEqualTo(2);
        assertThat(result.updated()).isEqualTo(0);
        assertThat(result.failed()).isEqualTo(0);
        // 验证 saveBatch 被调用且传入 2 条记录
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<ExchangeRateSnapshot>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(exchangeRateService).saveBatch(captor.capture(), anyInt());
        assertThat(captor.getValue()).hasSize(2);
        verify(exchangeRateService, atLeastOnce()).refreshCache();
    }

    @Test
    @DisplayName("CSV 导入：同日同币对已存在则更新")
    void importCsvUpdateExisting() {
        String csv = "日期,源币种,目标币种,汇率,来源\n"
                + "2026-07-01,USD,CNY,7.30,央行\n";
        MockMultipartFile file = new MockMultipartFile("file", "rates.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        // 已存在记录 → 走 updateBatchById
        ExchangeRateSnapshot existing = new ExchangeRateSnapshot();
        existing.setId(100L);
        existing.setRateDate(LocalDate.of(2026, 7, 1));
        existing.setFromCurrency("USD");
        existing.setToCurrency("CNY");
        existing.setRate(new BigDecimal("7.20"));
        when(exchangeRateSnapshotMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(existing));

        ExchangeRateImportService.ImportResult result = service.importRates(file);

        assertThat(result.inserted()).isEqualTo(0);
        assertThat(result.updated()).isEqualTo(1);
        verify(exchangeRateService).updateBatchById(anyList());
        // 验证更新后的汇率值
        assertThat(existing.getRate()).isEqualByComparingTo("7.30");
    }

    @Test
    @DisplayName("CSV 导入：英文表头也能识别")
    void importCsvEnglishHeaders() {
        String csv = "rate_date,from_currency,to_currency,rate,source\n"
                + "2026-07-01,USD,CNY,7.25,FED\n";
        MockMultipartFile file = new MockMultipartFile("file", "rates.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
        when(exchangeRateSnapshotMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        ExchangeRateImportService.ImportResult result = service.importRates(file);

        assertThat(result.inserted()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(0);
    }

    @Test
    @DisplayName("CSV 导入：无效汇率行进入错误明细")
    void importCsvInvalidRateRow() {
        String csv = "日期,源币种,目标币种,汇率,来源\n"
                + "2026-07-01,USD,CNY,abc,央行\n"      // 汇率非数字
                + "2026-07-02,USD,CNY,7.26,央行\n";     // 正常
        MockMultipartFile file = new MockMultipartFile("file", "rates.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
        when(exchangeRateSnapshotMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        ExchangeRateImportService.ImportResult result = service.importRates(file);

        assertThat(result.inserted()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).reason()).contains("汇率数值格式错误");
    }

    @Test
    @DisplayName("CSV 导入：表头完全无法识别抛异常")
    void importCsvUnrecognizedHeaders() {
        String csv = "colA,colB,colC\nv1,v2,v3\n";
        MockMultipartFile file = new MockMultipartFile("file", "rates.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.importRates(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未能识别任何汇率字段");
    }

    @Test
    @DisplayName("CSV 导入：日期格式 yyyy/MM/dd 也能解析")
    void importCsvSlashDateFormat() {
        String csv = "日期,源币种,目标币种,汇率\n"
                + "2026/07/01,USD,CNY,7.25\n";
        MockMultipartFile file = new MockMultipartFile("file", "rates.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
        when(exchangeRateSnapshotMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        ExchangeRateImportService.ImportResult result = service.importRates(file);

        assertThat(result.inserted()).isEqualTo(1);
        // 校验日期被正确解析（用 ArgumentCaptor 捕获 saveBatch 入参）
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<ExchangeRateSnapshot>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(exchangeRateService).saveBatch(captor.capture(), anyInt());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getRateDate()).isEqualTo(LocalDate.of(2026, 7, 1));
    }

    @Test
    @DisplayName("CSV 导入：来源字段为空时默认填'批量导入'")
    void importCsvDefaultSource() {
        String csv = "日期,源币种,目标币种,汇率\n"
                + "2026-07-01,USD,CNY,7.25\n";
        MockMultipartFile file = new MockMultipartFile("file", "rates.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
        when(exchangeRateSnapshotMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        service.importRates(file);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<ExchangeRateSnapshot>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(exchangeRateService).saveBatch(captor.capture(), anyInt());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getSource()).isEqualTo("批量导入");
    }
}
