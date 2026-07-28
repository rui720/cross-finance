package com.finance.platform.data.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.platform.common.utils.CurrencyConvertUtils;
import com.finance.platform.data.entity.ExchangeRateSnapshot;
import com.finance.platform.data.entity.ExtraCost;
import com.finance.platform.data.entity.RawOrder;
import com.finance.platform.accounting.entity.ProfitReport;
import com.finance.platform.accounting.vo.ProfitDetailVO;
import com.finance.platform.data.mapper.ExtraCostMapper;
import com.finance.platform.data.mapper.RawOrderMapper;
import com.finance.platform.data.vo.ReconcileResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 银行对账服务
 * <p>
 * 实现平台账单（source=PLATFORM）与银行流水（source=BANK）之间的自动对账。
 * <p>
 * 匹配策略：
 * <ol>
 *   <li>按订单号精确匹配：平台订单号 == 银行流水订单号（主要策略）</li>
 *   <li>按金额+日期匹配：平台理论应到账(CNY) ≈ 银行到账(CNY)，且日期在 ±7 天内（兜底）</li>
 *   <li>未匹配归类：平台未匹配→未到账，银行未匹配→不明入账</li>
 * </ol>
 * <p>
 * 到账判定逻辑：对每条平台账单，用 order_no 在银行流水中查找，
 * 找到则说明已到账，比对金额得出对账状态；找不到则为未到账。
 * <p>
 * 核心对账公式：<b>平台账单金额 = 银行流水 + 中转费 + 平台手续费</b>
 * <ul>
 *   <li>平台账单金额 = raw_order.amount 折算 CNY（买家应付总额，未扣任何费用）</li>
 *   <li>银行到账 = 银行流水 amount 折算 CNY（实际入账金额）</li>
 *   <li>中转费 = extra_cost 表中 order_no 关联、cost_type=TRANSFER_FEE、status=1 的记录 cny_amount 合计</li>
 *   <li>平台手续费 = raw_order.fee 按订单当日汇率折算 CNY（与清洗时同汇率）</li>
 *   <li>差值 = 平台账单金额 - 银行到账 - 中转费 - 平台手续费（理想为 0）</li>
 *   <li>|差值| ≤ 0.01 CNY → reconcile_status=1 已完成（容差内，保留 diff 供查看）</li>
 *   <li>|差值| > 0.01 CNY → reconcile_status=2 对账失败，记录 reconcile_diff</li>
 *   <li>平台未匹配 → reconcile_status=3 未到账，差值=平台理论应到账（账单金额-手续费-中转费）</li>
 *   <li>银行未匹配 → reconcile_status=4 不明入账，差值=-银行到账</li>
 * </ul>
 * <p>
 * 精度策略：全程使用 BigDecimal 全精度计算，不在中间环节 setScale 截断。
 * 数据库 DECIMAL(18,4) 支持 4 位小数，足够承载汇率折算结果。
 * 容差 0.01 CNY（1分钱）应对汇率折算精度、银行四舍五入等非真实业务差异。
 * 前端展示时再按 2 位小数格式化，不影响计算精度。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BankReconcileService {

    private final RawOrderMapper rawOrderMapper;
    private final ExtraCostMapper extraCostMapper;
    private final CurrencyConvertUtils currencyConvertUtils;
    private final ExchangeRateService exchangeRateService;

    /** 金额匹配容差（CNY，用于二级匹配的候选筛选） */
    private static final BigDecimal AMOUNT_TOLERANCE = new BigDecimal("0.01");
    /** 日期匹配窗口（天） */
    private static final int DATE_WINDOW_DAYS = 7;
    /**
     * 对账完成容差（CNY）：|差值| ≤ 0.01 视为对账完成。
     * <p>
     * 1分钱是人民币最小流通单位，1分以内的差异通常是汇率折算精度、银行四舍五入导致，
     * 非真实业务差异；1分以上需要人工核查。容差内仍保留 diff 值供查看，保持透明。
     */
    private static final BigDecimal DIFF_TOLERANCE = new BigDecimal("0.01");

    /**
     * 执行自动对账
     * <p>
     * 在指定日期范围内取平台账单（按 order_time 过滤），
     * 银行流水取全部（通过 order_no 关联，不按日期过滤）。
     * 匹配前先重置所有涉及记录的对账状态。
     *
     * @param startDate 起始日期（按平台账单 order_time 过滤）
     * @param endDate   结束日期
     * @return 对账结果汇总
     */
    @Transactional(rollbackFor = Exception.class)
    public ReconcileSummary autoReconcile(LocalDateTime startDate, LocalDateTime endDate) {
        log.info("[对账] 开始自动对账 range={} ~ {}", startDate, endDate);

        // 1. 查询范围内的平台账单（按 order_time 过滤，不依赖 clean_status）
        List<RawOrder> platformOrders = rawOrderMapper.selectList(new LambdaQueryWrapper<RawOrder>()
                .eq(RawOrder::getSource, "PLATFORM")
                .ge(startDate != null, RawOrder::getOrderTime, startDate)
                .le(endDate != null, RawOrder::getOrderTime, endDate));

        // 2. 查询所有银行流水（不按日期过滤，通过 order_no 关联）
        List<RawOrder> bankFlows = rawOrderMapper.selectList(new LambdaQueryWrapper<RawOrder>()
                .eq(RawOrder::getSource, "BANK"));

        log.info("[对账] 平台账单 {} 条，银行流水 {} 条", platformOrders.size(), bankFlows.size());

        if (platformOrders.isEmpty() && bankFlows.isEmpty()) {
            return new ReconcileSummary(0, 0, 0, 0, 0);
        }

        // 3. 重置所有涉及记录的对账状态
        List<Long> allIds = new ArrayList<>();
        platformOrders.forEach(o -> allIds.add(o.getId()));
        bankFlows.forEach(o -> allIds.add(o.getId()));
        if (!allIds.isEmpty()) {
            resetReconcileStatus(allIds);
        }

        // 4. 按 order_no 匹配（主要策略）
        // 用 Map 标记已匹配的记录ID，避免重复匹配
        Map<Long, Boolean> matchedPlatform = new HashMap<>();
        Map<Long, Boolean> matchedBank = new HashMap<>();

        int matched = 0;
        int amountDiff = 0;

        // 构建 bank orderNo → bank 索引
        Map<String, RawOrder> bankByOrderNo = new HashMap<>();
        for (RawOrder bank : bankFlows) {
            if (bank.getOrderNo() != null && !bank.getOrderNo().isBlank()) {
                bankByOrderNo.put(bank.getOrderNo().trim(), bank);
            }
        }
        for (RawOrder platform : platformOrders) {
            if (platform.getOrderNo() == null || platform.getOrderNo().isBlank()) continue;
            RawOrder bank = bankByOrderNo.get(platform.getOrderNo().trim());
            if (bank != null && !matchedBank.containsKey(bank.getId())) {
                boolean isMatched = compareAndSaveMatch(platform, bank);
                matchedPlatform.put(platform.getId(), true);
                matchedBank.put(bank.getId(), true);
                if (isMatched) matched++;
                else amountDiff++;
            }
        }

        // 5. 第二级：按金额+日期匹配（仅对第一级未匹配的记录，作为兜底）
        for (RawOrder platform : platformOrders) {
            if (matchedPlatform.containsKey(platform.getId())) continue;
            // 平台理论应到账金额（CNY）= 账单金额 - 平台手续费 - 中转费
            BigDecimal platformAmount = platformReceivableCny(platform);
            if (platformAmount == null) continue;

            LocalDateTime platformTime = platform.getOrderTime() != null
                    ? platform.getOrderTime() : platform.getSettleTime();
            if (platformTime == null) continue;

            RawOrder bestMatch = null;
            long minDaysDiff = Long.MAX_VALUE;
            for (RawOrder bank : bankFlows) {
                if (matchedBank.containsKey(bank.getId())) continue;
                BigDecimal bankAmount = bankReceivedCny(bank);
                if (bankAmount == null) continue;

                // 金额精确匹配（容差 0.01）
                if (platformAmount.subtract(bankAmount).abs().compareTo(AMOUNT_TOLERANCE) > 0) continue;

                // 日期在 ±7 天内
                LocalDateTime bankTime = bank.getSettleTime() != null
                        ? bank.getSettleTime() : bank.getOrderTime();
                if (bankTime == null) continue;
                long daysDiff = Math.abs(ChronoUnit.DAYS.between(platformTime.toLocalDate(), bankTime.toLocalDate()));
                if (daysDiff > DATE_WINDOW_DAYS) continue;

                // 选日期最接近的
                if (daysDiff < minDaysDiff) {
                    minDaysDiff = daysDiff;
                    bestMatch = bank;
                }
            }

            if (bestMatch != null) {
                boolean isMatched = compareAndSaveMatch(platform, bestMatch);
                matchedPlatform.put(platform.getId(), true);
                matchedBank.put(bestMatch.getId(), true);
                if (isMatched) matched++;
                else amountDiff++;
            }
        }

        // 6. 未匹配记录归类
        int platformOnly = 0;
        int bankOnly = 0;
        for (RawOrder platform : platformOrders) {
            if (!matchedPlatform.containsKey(platform.getId())) {
                platform.setReconcileStatus(RawOrder.RECONCILE_UNRECEIVED);
                platform.setReconcileMatchId(null);
                // 未到账：差值 = 平台理论应到账（银行到账视为 0）
                platform.setReconcileDiff(platformReceivableCny(platform));
                rawOrderMapper.updateById(platform);
                platformOnly++;
            }
        }
        for (RawOrder bank : bankFlows) {
            if (!matchedBank.containsKey(bank.getId())) {
                bank.setReconcileStatus(RawOrder.RECONCILE_UNKNOWN);
                bank.setReconcileMatchId(null);
                // 不明入账：差值 = -银行到账（平台应收视为 0，负值表示多收）
                BigDecimal bankAmt = bankReceivedCny(bank);
                bank.setReconcileDiff(bankAmt != null ? bankAmt.negate() : null);
                rawOrderMapper.updateById(bank);
                bankOnly++;
            }
        }

        ReconcileSummary summary = new ReconcileSummary(
                platformOrders.size() + bankFlows.size(),
                matched, amountDiff, platformOnly, bankOnly);
        log.info("[对账] 完成 {}", summary);
        return summary;
    }

    /**
     * 比较匹配的双方金额并保存对账结果
     * <p>
     * 核心公式：<b>平台账单金额 = 银行到账 + 中转费 + 平台手续费</b>
     * <p>
     * 差值 = 平台账单金额 - 银行到账 - 中转费 - 平台手续费
     * <p>
     * 全精度计算：BigDecimal 本身就是高精度类，不在中间环节 setScale 截断精度。
     * 前端展示时再按 2 位小数格式化，不影响计算精度。
     * <p>
     * 容差策略：|diff| ≤ DIFF_TOLERANCE → 对账完成（保留 diff 供查看，透明可见）
     *
     * @return true=已完成，false=对账失败
     */
    private boolean compareAndSaveMatch(RawOrder platform, RawOrder bank) {
        BigDecimal platformAmount = platformAmountCny(platform);
        BigDecimal bankAmount = bankReceivedCny(bank);
        BigDecimal platformFee = platformFeeCny(platform);
        BigDecimal transferFee = transferFeeCny(platform.getOrderNo());

        // 差值 = 平台账单金额 - (银行到账 + 平台手续费 + 中转费)，理想为 0
        // 全精度计算，不 setScale 截断
        BigDecimal diff = (platformAmount == null || bankAmount == null)
                ? null
                : platformAmount.subtract(bankAmount).subtract(platformFee).subtract(transferFee);

        // 容差判定：|diff| ≤ 0.01 CNY 视为对账完成
        // 1分钱以内的差异通常是汇率折算精度、银行四舍五入导致，非真实业务差异
        // 保留 diff 值入库，让用户能看到微小差异（透明），前端完成状态下用灰色小字显示
        boolean amountMatched = diff != null
                && diff.abs().compareTo(DIFF_TOLERANCE) <= 0;

        int status = amountMatched
                ? RawOrder.RECONCILE_DONE
                : RawOrder.RECONCILE_FAIL;
        // 容差内仍保留 diff 供查看；无 diff（null，无法计算）则置 null
        BigDecimal saveDiff = diff;

        // 更新平台账单
        platform.setReconcileStatus(status);
        platform.setReconcileMatchId(bank.getId());
        platform.setReconcileDiff(saveDiff);
        rawOrderMapper.updateById(platform);

        // 更新银行流水
        bank.setReconcileStatus(status);
        bank.setReconcileMatchId(platform.getId());
        bank.setReconcileDiff(saveDiff);
        rawOrderMapper.updateById(bank);

        return amountMatched;
    }

    /**
     * 平台账单总金额（CNY）：买家应付总额，不扣任何费用
     * <p>
     * 优先用清洗时写入的 settleAmount（按订单当日汇率折算，与清洗口径一致）；
     * settleAmount 为空（未清洗）时回退到 CurrencyConvertUtils（用最新汇率，不精确但兜底）。
     * <p>
     * 关键：不能用 CurrencyConvertUtils 的最新汇率折算历史订单，
     * 否则 7.1 的订单用 7.31 的汇率折算，差值会很大，导致对账全部失败。
     */
    private BigDecimal platformAmountCny(RawOrder platform) {
        // 优先用清洗结果（按订单当日汇率折算）
        if (platform.getSettleAmount() != null
                && platform.getSettleAmount().compareTo(BigDecimal.ZERO) != 0) {
            return platform.getSettleAmount();
        }
        // 兜底：未清洗的数据用最新汇率（不精确，但不会 NPE）
        BigDecimal amount = platform.getAmount();
        if (amount == null) return null;
        if ("CNY".equalsIgnoreCase(platform.getCurrency())) {
            return amount;
        }
        return currencyConvertUtils.toCny(amount, platform.getCurrency());
    }

    /**
     * 平台手续费（CNY）：raw_order.fee 折算 CNY
     * <p>
     * 关键：手续费与账单金额同币种，必须用同一个汇率折算，否则差值不对。
     * 直接用订单日期查当日汇率折算 fee（与 CurrencyConvertRule 清洗时用的同一个汇率），
     * 不再用 settleAmount 反推汇率——因为 settleAmount 若被截断，反推的汇率不精确。
     * <p>
     * 精度对齐：fee × rate 后 setScale(4)，与数据库 DECIMAL(18,4) 存储精度一致。
     * 这不是"人为截断精度"，而是"与存储精度对齐"——settleAmount、bank_received
     * 从数据库读出来都是 4 位小数，fee_cny 也必须是 4 位，否则 diff 会有 0.00001 级误差。
     * （之前的 bug 是 setScale(2)，比数据库精度还低，才产生 0.01 的虚假差值。）
     * fee 为空返回 0（不影响差值计算）。
     */
    private BigDecimal platformFeeCny(RawOrder platform) {
        BigDecimal fee = platform.getFee();
        if (fee == null || fee.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        if ("CNY".equalsIgnoreCase(platform.getCurrency())) {
            return fee;
        }
        // 直接查订单当日汇率，与清洗时用的汇率一致
        LocalDate rateDate = extractRateDate(platform);
        BigDecimal rate = findRateByDate(platform.getCurrency(), "CNY", rateDate);
        if (rate != null) {
            // setScale(4) 对齐数据库 DECIMAL(18,4) 存储精度
            return fee.multiply(rate).setScale(4, java.math.RoundingMode.HALF_UP);
        }
        // 兜底：未清洗或无汇率的数据用最新汇率
        return currencyConvertUtils.toCny(fee, platform.getCurrency());
    }

    /** 提取订单的汇率查询日期：优先用订单时间，无则用结算时间，都无则用当天 */
    private LocalDate extractRateDate(RawOrder order) {
        LocalDateTime t = order.getOrderTime();
        if (t == null) t = order.getSettleTime();
        return t != null ? t.toLocalDate() : LocalDate.now();
    }

    /**
     * 按日期查找汇率：优先精确匹配，无则取 date 之前最近的，再无则取之后最近的。
     * 与 CurrencyConvertRule.findRateByDate 逻辑一致，保证对账和清洗用同一汇率。
     */
    private BigDecimal findRateByDate(String from, String to, LocalDate date) {
        ExchangeRateSnapshot exact = exchangeRateService.getOne(new LambdaQueryWrapper<ExchangeRateSnapshot>()
                .eq(ExchangeRateSnapshot::getFromCurrency, from)
                .eq(ExchangeRateSnapshot::getToCurrency, to)
                .eq(ExchangeRateSnapshot::getRateDate, date)
                .last("LIMIT 1"));
        if (exact != null) return exact.getRate();

        ExchangeRateSnapshot before = exchangeRateService.getOne(new LambdaQueryWrapper<ExchangeRateSnapshot>()
                .eq(ExchangeRateSnapshot::getFromCurrency, from)
                .eq(ExchangeRateSnapshot::getToCurrency, to)
                .lt(ExchangeRateSnapshot::getRateDate, date)
                .orderByDesc(ExchangeRateSnapshot::getRateDate)
                .last("LIMIT 1"));
        if (before != null) return before.getRate();

        ExchangeRateSnapshot after = exchangeRateService.getOne(new LambdaQueryWrapper<ExchangeRateSnapshot>()
                .eq(ExchangeRateSnapshot::getFromCurrency, from)
                .eq(ExchangeRateSnapshot::getToCurrency, to)
                .gt(ExchangeRateSnapshot::getRateDate, date)
                .orderByAsc(ExchangeRateSnapshot::getRateDate)
                .last("LIMIT 1"));
        return after != null ? after.getRate() : null;
    }

    /**
     * 中转手续费（CNY）：从 extra_cost 表查询该订单关联的 TRANSFER_FEE 记录 cny_amount 合计
     * <p>
     * 查询条件：order_no 匹配 + cost_type=TRANSFER_FEE + status=1（生效）
     * <p>
     * 无记录或 orderNo 为空返回 0（不影响差值计算）
     */
    private BigDecimal transferFeeCny(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) return BigDecimal.ZERO;
        List<ExtraCost> transferFees = extraCostMapper.selectList(new LambdaQueryWrapper<ExtraCost>()
                .eq(ExtraCost::getOrderNo, orderNo.trim())
                .eq(ExtraCost::getCostType, "TRANSFER_FEE")
                .eq(ExtraCost::getStatus, 1));
        if (transferFees.isEmpty()) return BigDecimal.ZERO;
        return transferFees.stream()
                .map(ExtraCost::getCnyAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 平台理论应到账（CNY）：= 平台账单金额 - 平台手续费 - 中转费
     * <p>
     * 用于「未到账」场景的差值计算（银行到账视为 0，差值即理论应到账金额），
     * 以及二级匹配时与银行到账金额比对的基准。
     */
    private BigDecimal platformReceivableCny(RawOrder platform) {
        BigDecimal platformAmount = platformAmountCny(platform);
        if (platformAmount == null) return null;
        return platformAmount
                .subtract(platformFeeCny(platform))
                .subtract(transferFeeCny(platform.getOrderNo()));
    }

    /**
     * 计算银行到账金额（CNY）：
     * <ul>
     *   <li>银行币种为 CNY → 直接用 amount</li>
     *   <li>否则用 amount 折算 CNY</li>
     * </ul>
     */
    private BigDecimal bankReceivedCny(RawOrder bank) {
        BigDecimal amount = bank.getAmount();
        if (amount == null) return null;
        if ("CNY".equalsIgnoreCase(bank.getCurrency())) {
            return amount;
        }
        return currencyConvertUtils.toCny(amount, bank.getCurrency());
    }

    /**
     * 重置对账状态：清空对账状态、匹配ID、差值
     */
    private void resetReconcileStatus(List<Long> ids) {
        for (Long id : ids) {
            RawOrder update = new RawOrder();
            update.setId(id);
            update.setReconcileStatus(RawOrder.RECONCILE_NONE);
            update.setReconcileMatchId(null);
            update.setReconcileDiff(null);
            rawOrderMapper.updateById(update);
        }
    }

    /**
     * 手动匹配：用户指定一条平台订单和一条银行流水进行匹配
     */
    @Transactional(rollbackFor = Exception.class)
    public void manualMatch(Long platformOrderId, Long bankFlowId) {
        RawOrder platform = rawOrderMapper.selectById(platformOrderId);
        RawOrder bank = rawOrderMapper.selectById(bankFlowId);
        if (platform == null || bank == null) {
            throw new IllegalArgumentException("订单或银行流水不存在");
        }
        if (!"PLATFORM".equals(platform.getSource()) || !"BANK".equals(bank.getSource())) {
            throw new IllegalArgumentException("请选择一条平台订单和一条银行流水进行匹配");
        }
        compareAndSaveMatch(platform, bank);
        log.info("[对账] 手动匹配 platform={}, bank={}", platformOrderId, bankFlowId);
    }

    /**
     * 标记差异已处理（用户确认差异项已解决）
     * <p>
     * 将 reconcileStatus 置为已完成(1)，并清除差值。
     */
    @Transactional(rollbackFor = Exception.class)
    public void resolveDiff(List<Long> ids) {
        for (Long id : ids) {
            RawOrder update = new RawOrder();
            update.setId(id);
            update.setReconcileStatus(RawOrder.RECONCILE_DONE);
            update.setReconcileDiff(null);
            rawOrderMapper.updateById(update);
        }
        log.info("[对账] 标记差异已处理 ids={}", ids);
    }

    /**
     * 取消对账：重置为未对账状态
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancelReconcile(List<Long> ids) {
        resetReconcileStatus(ids);
        log.info("[对账] 取消对账 ids={}", ids);
    }

    /**
     * 以平台账单为主体构建对账聚合视图
     * <p>
     * 按订单号关联银行流水与中转费，5 个 CNY 金额列集中填充，方便前端比对：
     * <ul>
     *   <li>账单金额 CNY = settleAmount（清洗时按订单当日汇率折算）</li>
     *   <li>平台手续费 CNY = fee × 当日汇率（与对账计算同口径）</li>
     *   <li>中转费 CNY = extra_cost 中 TRANSFER_FEE 的 cny_amount 合计</li>
     *   <li>银行到账 CNY = 银行流水 amount 折算 CNY；未到账为 null</li>
     *   <li>差值 = 平台账单 - 银行到账 - 中转费 - 平台手续费（取对账时已存的 reconcileDiff）</li>
     * </ul>
     * 折算口径与 {@link #compareAndSaveMatch} 完全一致，保证展示与判定一致。
     *
     * @param platform 平台账单记录
     * @return 聚合视图
     */
    public ReconcileResultVO buildReconcileResult(RawOrder platform) {
        ReconcileResultVO vo = new ReconcileResultVO();
        vo.setId(platform.getId());
        vo.setOrderNo(platform.getOrderNo());
        vo.setPlatform(platform.getPlatform());
        vo.setCurrency(platform.getCurrency());
        vo.setAmount(platform.getAmount());
        vo.setFee(platform.getFee());
        vo.setOrderTime(platform.getOrderTime());
        vo.setSettleTime(platform.getSettleTime());
        vo.setBatchNo(platform.getBatchNo());
        vo.setReconcileStatus(platform.getReconcileStatus());
        vo.setReconcileMatchId(platform.getReconcileMatchId());

        // 5 个 CNY 金额列
        vo.setPlatformAmountCny(platformAmountCny(platform));
        vo.setPlatformFeeCny(platformFeeCny(platform));
        vo.setTransferFeeCny(transferFeeCny(platform.getOrderNo()));

        // 按订单号查银行流水，填充银行到账 CNY
        if (platform.getOrderNo() != null && !platform.getOrderNo().isBlank()) {
            RawOrder bank = rawOrderMapper.selectOne(new LambdaQueryWrapper<RawOrder>()
                    .eq(RawOrder::getSource, "BANK")
                    .eq(RawOrder::getOrderNo, platform.getOrderNo().trim())
                    .last("LIMIT 1"));
            if (bank != null) {
                vo.setBankReceivedCny(bankReceivedCny(bank));
            }
        }

        vo.setReconcileDiff(platform.getReconcileDiff());
        return vo;
    }

    /**
     * 以纯银行流水为主体构建对账聚合视图（用于"不明入账"场景：平台无对应账单）
     * <p>
     * 此时账单金额/手续费/中转费为 null，仅银行到账有值，差值 = -银行到账。
     *
     * @param bank 银行流水记录
     * @return 聚合视图
     */
    public ReconcileResultVO buildReconcileResultFromBank(RawOrder bank) {
        ReconcileResultVO vo = new ReconcileResultVO();
        vo.setId(bank.getId());
        vo.setOrderNo(bank.getOrderNo());
        vo.setCurrency(bank.getCurrency());
        vo.setAmount(bank.getAmount());
        vo.setOrderTime(bank.getOrderTime());
        vo.setSettleTime(bank.getSettleTime());
        vo.setBatchNo(bank.getBatchNo());
        vo.setReconcileStatus(bank.getReconcileStatus());
        vo.setReconcileMatchId(bank.getReconcileMatchId());

        vo.setBankReceivedCny(bankReceivedCny(bank));
        vo.setReconcileDiff(bank.getReconcileDiff());
        return vo;
    }

    /**
     * 以利润报表为主体构建利润明细聚合视图
     * <p>
     * 利润明细页以账单为主体，按订单号关联银行流水、中转费，集中展示完整利润信息：
     * <ul>
     *   <li>账单金额 CNY / 平台手续费 CNY：优先取自 profit_report 已核算字段（cnyAmount / feeCost），
     *       与核算口径一致；无核算数据时回退到 raw_order 实时计算</li>
     *   <li>中转费 CNY：extra_cost 中 TRANSFER_FEE 的 cny_amount 合计</li>
     *   <li>银行到账 CNY：按订单号查银行流水，未到账为 null（与对账模式口径一致）</li>
     *   <li>对账差值：取自平台账单 reconcile_diff（对账时已存）</li>
     *   <li>公共分摊 / 直接成本 / 总成本 / 利润 / 利润率：取自 profit_report</li>
     * </ul>
     * 折算口径与 {@link #buildReconcileResult} 一致，保证利润明细与对账模式展示一致。
     *
     * @param report 利润报表记录（已核算）
     * @return 利润明细聚合视图
     */
    public ProfitDetailVO buildProfitDetail(ProfitReport report) {
        ProfitDetailVO vo = new ProfitDetailVO();
        vo.setOrderNo(report.getOrderNo());
        vo.setPlatform(report.getPlatform());
        vo.setShopId(report.getShopId());
        vo.setCurrency(report.getCurrency());
        vo.setOrderTime(report.getOrderTime());
        vo.setReconcileStatus(report.getReconcileStatus());

        // 利润核算字段（直接取自 profit_report，与核算口径一致）
        vo.setPlatformAmountCny(report.getCnyAmount());
        vo.setPlatformFeeCny(report.getFeeCost());
        vo.setSharedCost(report.getSharedCost());
        vo.setDirectCost(report.getDirectCost());
        vo.setCostAmount(report.getCostAmount());
        vo.setProfitAmount(report.getProfitAmount());
        vo.setProfitRate(report.getProfitRate());
        vo.setActualReceivedAmount(report.getActualReceivedAmount());

        // 按订单号关联平台账单（取 id、批次号、对账差值）
        if (report.getOrderNo() != null && !report.getOrderNo().isBlank()) {
            RawOrder platform = rawOrderMapper.selectOne(new LambdaQueryWrapper<RawOrder>()
                    .eq(RawOrder::getSource, "PLATFORM")
                    .eq(RawOrder::getOrderNo, report.getOrderNo().trim())
                    .last("LIMIT 1"));
            if (platform != null) {
                vo.setId(platform.getId());
                vo.setBatchNo(platform.getBatchNo());
                vo.setReconcileDiff(platform.getReconcileDiff());
            }
            // 中转费：extra_cost 中 TRANSFER_FEE 合计
            vo.setTransferFeeCny(transferFeeCny(report.getOrderNo()));
            // 银行到账：按订单号查银行流水，未到账为 null（与对账模式口径一致）
            RawOrder bank = rawOrderMapper.selectOne(new LambdaQueryWrapper<RawOrder>()
                    .eq(RawOrder::getSource, "BANK")
                    .eq(RawOrder::getOrderNo, report.getOrderNo().trim())
                    .last("LIMIT 1"));
            if (bank != null) {
                vo.setBankReceivedCny(bankReceivedCny(bank));
            }
        }
        return vo;
    }

    /**
     * 对账结果汇总记录
     */
    public record ReconcileSummary(
            int total,
            int matched,
            int amountDiff,
            int platformOnly,
            int bankOnly
    ) {}
}
