<script setup>
// 利润诊断页 — 四维度诊断 + 亏损订单（只读视图）
// 职责：
//   1. 四维度诊断：A.结构占比 / B.趋势与波动 / C.成本合理性 / D.可执行行动点
//   2. 亏损订单 Top N 清单（按利润升序，亏损最多的排前）
// 数据来源：已核算入库的 profit_report，本页不触发核算
// diagnoseProfit 接口 period 兼容 YYYYMM（月份）和 "startDate~endDate"（范围）
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { diagnoseProfit, getLossOrders } from '@/api/accounting'
import EChartsWrapper from '@/components/EChartsWrapper.vue'
import { usePageState } from '@/composables/usePageState'
import { useAccountingStore } from '@/store/accounting'

const router = useRouter()
// 页面状态保持（核算模式、周期、日期范围、亏损订单条数切换页面后自动恢复）
const { loadField, saveField } = usePageState('ProfitDiagnosis')
// 业财核算共享状态：监听「利润明细」页核算成功后同步周期
const accountingStore = useAccountingStore()

// 核算模式：MONTH 按月份 / RANGE 按日期范围
const calcMode = ref(loadField('calcMode', 'MONTH'))
// 月份周期（MONTH 模式）：默认上个月（YYYYMM）
function getLastMonthPeriod() {
  const now = new Date()
  const year = now.getFullYear()
  const month = now.getMonth() // 0-11，当前月=month+1，上个月=month
  if (month === 0) {
    return `${year - 1}12`
  }
  return `${year}${String(month).padStart(2, '0')}`
}
const period = ref(loadField('period', getLastMonthPeriod()))
// 日期范围（RANGE 模式），[startDate, endDate]
const dateRange = ref(loadField('dateRange', []))

// 诊断数据
const diagnosis = ref(null)
const diagnosisLoading = ref(false)
// 亏损订单 Top N
const lossOrders = ref([])
const lossOrdersLoading = ref(false)
const lossOrderLimit = ref(loadField('lossOrderLimit', 10))
// 是否已尝试加载（用于区分"加载中"与"无数据"两种空状态）
const hasLoaded = ref(false)

// 金额格式化
function formatMoney(num) {
  if (num === null || num === undefined || num === '') return '-'
  const n = Number(num)
  if (isNaN(n)) return '-'
  return n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

// 利润率格式化（0-1 → xx.xx%）
function formatRate(num) {
  if (num === null || num === undefined || num === '') return '-'
  const n = Number(num)
  if (isNaN(n)) return '-'
  // 兼容 0-1 与 0-100 两种输入
  const pct = n <= 1 ? n * 100 : n
  return pct.toFixed(2) + '%'
}

// 占比格式化（0-1 → xx.x%）
function formatShare(num) {
  if (num === null || num === undefined || num === '') return '-'
  const n = Number(num)
  if (isNaN(n)) return '-'
  return (n * 100).toFixed(1) + '%'
}

// 百分比变化格式化（已为百分比数字 → +/-xx.xx%）
function formatDelta(num) {
  if (num === null || num === undefined || num === '') return '-'
  const n = Number(num)
  if (isNaN(n)) return '-'
  return (n >= 0 ? '+' : '') + n.toFixed(2) + '%'
}

// 对账状态码 → 标签 + 颜色（用扩展的 reconcile_status 值替代原 reconcile_type 语义）
const RECONCILE_STATUS_MAP = {
  0: { text: '未对账', type: 'info' },
  1: { text: '已完成', type: 'success' },
  2: { text: '对账失败', type: 'danger' },
  3: { text: '未到账', type: 'warning' },
  4: { text: '不明入账', type: 'warning' }
}
function reconcileStatusTag(status) {
  return RECONCILE_STATUS_MAP[status] || { text: '未对账', type: 'info' }
}

// 切换模式时保留另一模式的周期值（切回来还能用），只清空当前展示数据
function handleModeChange() {
  diagnosis.value = null
  lossOrders.value = []
  hasLoaded.value = false
  // 若新模式已有有效周期，自动加载
  const params = buildPeriodParams()
  if (params) {
    loadAll()
  }
}

/**
 * 构造查询所需的 period / startDate+endDate 参数。
 * MONTH 模式返回 { period }；RANGE 模式返回 { startDate, endDate }；无有效周期返回 null。
 */
function buildPeriodParams() {
  if (calcMode.value === 'MONTH' && period.value) {
    return { period: period.value }
  }
  if (calcMode.value === 'RANGE' && dateRange.value && dateRange.value.length === 2) {
    return { startDate: dateRange.value[0], endDate: dateRange.value[1] }
  }
  return null
}

// 加载诊断数据（MONTH 与 RANGE 模式均可用）
async function loadDiagnosis() {
  // 计算诊断所需的 period 参数：MONTH 用月份，RANGE 用 "startDate~endDate"
  let diagPeriod = null
  if (calcMode.value === 'MONTH' && period.value) {
    diagPeriod = period.value
  } else if (calcMode.value === 'RANGE' && dateRange.value && dateRange.value.length === 2) {
    diagPeriod = `${dateRange.value[0]}~${dateRange.value[1]}`
  }
  if (!diagPeriod) {
    diagnosis.value = null
    return
  }
  diagnosisLoading.value = true
  try {
    const res = await diagnoseProfit(diagPeriod)
    diagnosis.value = res.data
  } catch (e) {
    // 错误已由请求拦截器统一处理
  } finally {
    diagnosisLoading.value = false
  }
}

// 加载亏损订单 Top N
async function loadLossOrders() {
  const params = buildPeriodParams()
  if (!params) {
    lossOrders.value = []
    return
  }
  lossOrdersLoading.value = true
  try {
    const res = await getLossOrders({ ...params, limit: lossOrderLimit.value })
    lossOrders.value = res.data || []
  } catch (e) {
    // 错误已由请求拦截器统一处理
  } finally {
    lossOrdersLoading.value = false
  }
}

// 切换亏损订单显示条数
async function handleLossLimitChange() {
  await loadLossOrders()
}

// 并行加载诊断 + 亏损订单
async function loadAll() {
  await Promise.all([loadDiagnosis(), loadLossOrders()])
  hasLoaded.value = true
}

// 跳转到利润明细页执行核算
function goToCalculate() {
  router.push('/accounting/profit-detail')
}

// 是否无数据（已加载但诊断结果为空且无亏损订单）→ 显示未核算提示
const noData = computed(() => {
  if (!hasLoaded.value) return false
  return !diagnosis.value && lossOrders.value.length === 0
})

// A 维度：结构占比图
const structureOption = computed(() => {
  const data = diagnosis.value?.structure?.byPlatform || []
  if (data.length === 0) {
    return { title: { text: 'A. 平台利润结构', left: 'left' }, graphic: { type: 'text', left: 'center', top: 'middle', style: { text: '暂无数据', fill: '#909399' } } }
  }
  return {
    title: { text: 'A. 平台利润结构占比', left: 'left', textStyle: { fontSize: 14 } },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    legend: { data: ['营收占比', '利润占比', '利润率'], top: 0, right: 0 },
    grid: { left: 60, right: 20, top: 50, bottom: 40 },
    xAxis: { type: 'category', data: data.map(d => d.platform) },
    yAxis: [
      { type: 'value', name: '占比', axisLabel: { formatter: v => (v * 100).toFixed(0) + '%' } },
      { type: 'value', name: '利润率', axisLabel: { formatter: v => (v * 100).toFixed(0) + '%' } }
    ],
    series: [
      {
        name: '营收占比',
        type: 'bar',
        data: data.map(d => Number(d.revenueShare)),
        itemStyle: { color: '#409eff' }
      },
      {
        name: '利润占比',
        type: 'bar',
        data: data.map(d => Number(d.profitShare)),
        itemStyle: { color: '#67c23a' }
      },
      {
        name: '利润率',
        type: 'line',
        yAxisIndex: 1,
        data: data.map(d => Number(d.profitRate)),
        itemStyle: { color: '#e6a23c' },
        lineStyle: { width: 2 }
      }
    ]
  }
})

// 行动点等级颜色映射
const levelColor = { INFO: '#409eff', WARN: '#e6a23c', DANGER: '#f56c6c' }
const levelTagType = { INFO: 'info', WARN: 'warning', DANGER: 'danger' }

// 趋势信号颜色
const trendColor = computed(() => {
  const s = diagnosis.value?.trend?.trendSignal
  if (s === 'IMPROVING') return '#67c23a'
  if (s === 'DECLINING') return '#f56c6c'
  if (s === 'STABLE') return '#409eff'
  return '#909399'
})

// 周期变化时自动加载（仅 MONTH 模式）
watch(period, () => {
  if (calcMode.value === 'MONTH' && period.value) {
    loadAll()
  }
})

// 日期范围变化时自动加载（仅 RANGE 模式）
watch(dateRange, () => {
  if (calcMode.value === 'RANGE' && dateRange.value && dateRange.value.length === 2) {
    loadAll()
  }
})

// 监听「利润明细」页核算成功：同步本次核算的周期到本页
// 同步后 calcMode/period/dateRange 变化会触发上面的 watch → loadAll，无需手动调用
watch(() => accountingStore.calcVersion, (v) => {
  if (v <= 0) return
  calcMode.value = accountingStore.lastCalcMode
  if (accountingStore.lastCalcMode === 'MONTH') {
    period.value = accountingStore.lastCalcPeriod
    dateRange.value = []
  } else {
    period.value = ''
    dateRange.value = accountingStore.lastCalcDateRange
  }
})

/* ==================== 页面状态持久化 ==================== */
watch(calcMode, v => saveField('calcMode', v))
watch(period, v => saveField('period', v))
watch(dateRange, v => saveField('dateRange', v), { deep: true })
watch(lossOrderLimit, v => saveField('lossOrderLimit', v))

onMounted(() => {
  // 若已发生过核算（从其他页面核算后切过来），同步最近核算的周期到本页
  if (accountingStore.calcVersion > 0) {
    calcMode.value = accountingStore.lastCalcMode
    if (accountingStore.lastCalcMode === 'MONTH') {
      period.value = accountingStore.lastCalcPeriod
    } else {
      dateRange.value = accountingStore.lastCalcDateRange
    }
  }
  // 初始化时加载诊断和亏损订单
  loadAll()
})
</script>

<template>
  <div>
    <!-- 顶部：核算模式 + 周期选择器 -->
    <div class="page-card mb-16">
      <el-form :inline="true">
        <el-form-item label="核算模式">
          <el-radio-group v-model="calcMode" @change="handleModeChange">
            <el-radio-button value="MONTH">按月份</el-radio-button>
            <el-radio-button value="RANGE">按日期范围</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="calcMode === 'MONTH'" label="核算周期">
          <el-date-picker
            v-model="period"
            type="month"
            format="YYYYMM"
            value-format="YYYYMM"
            placeholder="请选择核算周期"
            style="width: 180px"
          />
        </el-form-item>
        <el-form-item v-else label="核算日期范围">
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            value-format="YYYY-MM-DD"
            style="width: 280px"
          />
        </el-form-item>
        <el-form-item>
          <el-tooltip content="本页为只读视图，数据来源为已核算入库的 profit_report；如无数据请先在利润明细页执行核算" placement="top">
            <el-icon style="color: #909399; font-size: 16px"><InfoFilled /></el-icon>
          </el-tooltip>
        </el-form-item>
      </el-form>
    </div>

    <!-- 未核算提示：检测到该周期无报表数据时显示 -->
    <div v-if="noData" class="page-card mb-16">
      <el-alert type="warning" :closable="false">
        <template #title>
          <span style="font-weight: 600">当前周期暂无已核算的利润数据</span>
        </template>
        <template #default>
          <div style="margin-top: 4px">请先在【利润明细】页执行核算，核算完成后即可查看利润诊断与亏损订单。</div>
          <el-button type="primary" size="small" style="margin-top: 8px" @click="goToCalculate">
            <el-icon><Right /></el-icon> 前往利润明细页
          </el-button>
        </template>
      </el-alert>
    </div>

    <template v-if="!noData">
      <!-- A 维度：结构占比（独占一行） -->
      <div class="page-card mb-16" v-loading="diagnosisLoading">
        <div class="section-title">
        <span>结构占比</span>
      </div>
        <el-empty v-if="!diagnosis?.structure?.byPlatform?.length" description="选择核算周期后展示平台结构占比" :image-size="60" />
        <template v-else>
          <EChartsWrapper :option="structureOption" height="380px" />
          <!-- 健康标记 -->
          <div v-if="diagnosis.structure.healthFlags.length" style="margin-top: 12px">
            <el-alert
              v-for="(flag, idx) in diagnosis.structure.healthFlags"
              :key="idx"
              :type="flag.level === 'DANGER' ? 'error' : (flag.level === 'WARN' ? 'warning' : 'info')"
              :title="flag.message"
              :closable="false"
              style="margin-bottom: 8px"
            />
          </div>
          <!-- 平台结构明细表 -->
          <el-table :data="diagnosis.structure.byPlatform" border stripe size="small" style="margin-top: 8px">
            <el-table-column prop="platform" label="平台" width="100" />
            <el-table-column label="营收 (CNY)" align="right" min-width="115">
              <template #default="{ row }">¥ {{ formatMoney(row.revenue) }}</template>
            </el-table-column>
            <el-table-column label="成本 (CNY)" align="right" min-width="115">
              <template #default="{ row }">¥ {{ formatMoney(row.cost) }}</template>
            </el-table-column>
            <el-table-column label="利润 (CNY)" align="right" min-width="115">
              <template #default="{ row }">
                <span :style="{ color: row.profit >= 0 ? '#67c23a' : '#f56c6c' }">¥ {{ formatMoney(row.profit) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="营收占比" align="right" width="85">
              <template #default="{ row }">{{ formatShare(row.revenueShare) }}</template>
            </el-table-column>
            <el-table-column label="利润占比" align="right" width="85">
              <template #default="{ row }">{{ formatShare(row.profitShare) }}</template>
            </el-table-column>
            <el-table-column label="利润率" align="right" width="85">
              <template #default="{ row }">
                <span :style="{ color: row.profitRate >= 0 ? '#67c23a' : '#f56c6c' }">{{ formatRate(row.profitRate) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="增收不增利" align="right" width="105">
              <template #default="{ row }">
                <el-tag v-if="row.revenueProfitGap > 10" type="warning" size="small">
                  +{{ row.revenueProfitGap }} pp
                </el-tag>
                <span v-else style="color: #909399">{{ row.revenueProfitGap }} pp</span>
              </template>
            </el-table-column>
          </el-table>
        </template>
      </div>

      <!-- B 维度：趋势与波动（独占一行，6 个指标卡横排） -->
      <div class="page-card mb-16" v-loading="diagnosisLoading">
        <div class="section-title">
        <span>趋势与波动</span>
      </div>
        <el-empty v-if="!diagnosis?.trend" description="选择核算周期后展示环比趋势" :image-size="60" />
        <template v-else-if="diagnosis.trend.trendSignal === 'NO_HISTORY'">
          <el-alert type="info" :title="diagnosis.trend.explanation" :closable="false" />
        </template>
        <template v-else>
          <el-alert
            :type="diagnosis.trend.trendSignal === 'IMPROVING' ? 'success' : (diagnosis.trend.trendSignal === 'DECLINING' ? 'error' : 'info')"
            :title="diagnosis.trend.explanation"
            :closable="false"
            style="margin-bottom: 16px"
          />
          <div class="trend-grid">
            <div class="trend-item">
              <div class="trend-label">上期利润率 ({{ diagnosis.trend.previousPeriod }})</div>
              <div class="trend-value">{{ formatRate(diagnosis.trend.previousProfitRate) }}</div>
            </div>
            <div class="trend-item">
              <div class="trend-label">本期利润率 ({{ diagnosis.trend.currentPeriod }})</div>
              <div class="trend-value" :style="{ color: trendColor }">{{ formatRate(diagnosis.trend.currentProfitRate) }}</div>
            </div>
            <div class="trend-item">
              <div class="trend-label">利润率变化</div>
              <div class="trend-value" :style="{ color: trendColor }">
                {{ diagnosis.trend.profitRateDelta >= 0 ? '+' : '' }}{{ diagnosis.trend.profitRateDelta }} pp
              </div>
            </div>
            <div class="trend-item">
              <div class="trend-label">营收环比</div>
              <div class="trend-value" :style="{ color: diagnosis.trend.revenueDelta >= 0 ? '#67c23a' : '#f56c6c' }">
                {{ formatDelta(diagnosis.trend.revenueDelta) }}
              </div>
            </div>
            <div class="trend-item">
              <div class="trend-label">成本环比</div>
              <div class="trend-value" :style="{ color: diagnosis.trend.costDelta >= 0 ? '#f56c6c' : '#67c23a' }">
                {{ formatDelta(diagnosis.trend.costDelta) }}
              </div>
            </div>
            <div class="trend-item">
              <div class="trend-label">利润环比</div>
              <div class="trend-value" :style="{ color: diagnosis.trend.profitDelta >= 0 ? '#67c23a' : '#f56c6c' }">
                {{ formatDelta(diagnosis.trend.profitDelta) }}
              </div>
            </div>
          </div>
        </template>
      </div>

      <!-- C 维度：成本合理性（独占一行，el-descriptions 横向铺开） -->
      <div class="page-card mb-16" v-loading="diagnosisLoading">
        <div class="section-title">
        <span>成本合理性</span>
      </div>
        <el-empty v-if="!diagnosis?.cost" description="选择核算周期后展示成本诊断" :image-size="60" />
        <template v-else>
          <el-descriptions :column="3" border size="small" style="margin-bottom: 12px">
            <el-descriptions-item label="当前分摊规则">
              {{ diagnosis.cost.currentRuleName }} ({{ diagnosis.cost.currentRuleType }})
            </el-descriptions-item>
            <el-descriptions-item label="平均成本率">
              {{ formatRate(diagnosis.cost.avgCostRate) }}
            </el-descriptions-item>
            <el-descriptions-item label="高客单价订单被多摊成本">
              <span v-if="diagnosis.cost.highTicketOrders > 0" style="color: #e6a23c">
                {{ diagnosis.cost.highTicketOrders }} 笔 / ¥ {{ formatMoney(diagnosis.cost.highTicketOverCost) }}
              </span>
              <span v-else style="color: #67c23a">无明显扭曲</span>
            </el-descriptions-item>
            <el-descriptions-item label="成本率最高平台">
              {{ diagnosis.cost.maxCostRatePlatform || '-' }} ({{ formatRate(diagnosis.cost.maxCostRate) }})
            </el-descriptions-item>
            <el-descriptions-item label="成本率最低平台">
              {{ diagnosis.cost.minCostRatePlatform || '-' }} ({{ formatRate(diagnosis.cost.minCostRate) }})
            </el-descriptions-item>
            <el-descriptions-item label="平台间成本率差异">
              <span v-if="diagnosis.cost.maxCostRate - diagnosis.cost.minCostRate > 0.3" style="color: #f56c6c">显著</span>
              <span v-else style="color: #67c23a">合理</span>
            </el-descriptions-item>
          </el-descriptions>
          <div class="suggestions">
            <div v-for="(s, idx) in diagnosis.cost.suggestions" :key="idx" class="suggestion-item">
              <el-icon><InfoFilled /></el-icon>
              <span>{{ s }}</span>
            </div>
          </div>
        </template>
      </div>

      <!-- D 维度：行动点（独占一行，action-item 全宽展示） -->
      <div class="page-card mb-16" v-loading="diagnosisLoading">
        <div class="section-title">
        <span>可执行的行动点</span>
      </div>
        <el-empty v-if="!diagnosis?.actionPoints?.length" description="暂无行动建议" :image-size="60" />
        <template v-else>
          <div v-for="(ap, idx) in diagnosis.actionPoints" :key="idx" class="action-item">
            <div class="action-header">
              <el-tag :type="levelTagType[ap.level]" size="small">{{ ap.level }}</el-tag>
              <el-tag type="info" size="small" effect="plain">{{ ap.category }}</el-tag>
              <span class="action-title">{{ ap.title }}</span>
            </div>
            <div class="action-detail">{{ ap.detail }}</div>
            <div class="action-suggestion">
              <el-icon><Pointer /></el-icon>
              <span>建议动作：{{ ap.suggestedAction }}</span>
            </div>
          </div>
        </template>
      </div>

      <!-- E 亏损订单清单 Top N（独占一行，编号连续 A/B/C/D/E） -->
      <div class="page-card mb-16" v-loading="lossOrdersLoading">
        <div class="section-title">
          <span>亏损订单清单</span>
          <el-select v-model="lossOrderLimit" size="small" style="width: 110px; margin-left: 16px"
                     @change="handleLossLimitChange">
            <el-option :value="5" label="Top 5" />
            <el-option :value="10" label="Top 10" />
            <el-option :value="20" label="Top 20" />
            <el-option :value="50" label="Top 50" />
          </el-select>
        </div>
        <el-empty v-if="!lossOrders.length" description="本期无亏损订单" :image-size="60" />
        <template v-else>
          <el-table :data="lossOrders" border stripe size="small" max-height="560">
            <el-table-column type="index" label="#" width="50" align="center" />
            <el-table-column prop="order_no" label="订单号" min-width="180" show-overflow-tooltip />
            <el-table-column prop="platform" label="平台" width="120" />
            <el-table-column prop="shop_id" label="店铺" width="120" show-overflow-tooltip />
            <el-table-column label="CNY 金额" width="130" align="right">
              <template #default="{ row }">¥ {{ formatMoney(row.cny_amount) }}</template>
            </el-table-column>
            <el-table-column label="成本" width="130" align="right">
              <template #default="{ row }">¥ {{ formatMoney(row.cost_amount) }}</template>
            </el-table-column>
            <el-table-column label="亏损金额" width="130" align="right">
              <template #default="{ row }">
                <span style="color: #f56c6c; font-weight: 600">¥ {{ formatMoney(row.profit_amount) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="利润率" width="100" align="right">
              <template #default="{ row }">
                <span style="color: #f56c6c">{{ formatRate(row.profit_rate) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="对账状态" width="100" align="center">
              <template #default="{ row }">
                <el-tag :type="reconcileStatusTag(row.reconcile_status).type" size="small">
                  {{ reconcileStatusTag(row.reconcile_status).text }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </template>
      </div>
    </template>
  </div>
</template>

<style scoped>
.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 12px;
  color: #303133;
}
.section-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  color: #fff;
  font-size: 13px;
  font-weight: 700;
}
.badge-a { background: #409eff; }
.badge-b { background: #67c23a; }
.badge-c { background: #e6a23c; }
.badge-d { background: #f56c6c; }
.badge-e { background: #909399; }

/* 趋势网格：6 列横排（独占一行后充分利用横向空间） */
.trend-grid {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 12px;
}
.trend-item {
  background: #f5f7fa;
  padding: 16px;
  border-radius: 4px;
  text-align: center;
  min-width: 160px;
}
.trend-label {
  font-size: 12px;
  color: #909399;
  margin-bottom: 8px;
}
.trend-value {
  font-size: 20px;
  font-weight: 600;
  color: #303133;
}

/* 成本诊断建议 */
.suggestions {
  background: #f5f7fa;
  padding: 12px 16px;
  border-radius: 4px;
}
.suggestion-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 13px;
  color: #606266;
  line-height: 1.6;
  margin-bottom: 6px;
}
.suggestion-item:last-child {
  margin-bottom: 0;
}

/* 行动点 */
.action-item {
  border-left: 3px solid #dcdfe6;
  padding: 12px 16px;
  margin-bottom: 12px;
  background: #fafafa;
  border-radius: 0 4px 4px 0;
}
.action-item:last-child {
  margin-bottom: 0;
}
.action-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.action-title {
  font-weight: 600;
  color: #303133;
}
.action-detail {
  font-size: 13px;
  color: #606266;
  margin-bottom: 6px;
  line-height: 1.6;
}
.action-suggestion {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #409eff;
}

@media (max-width: 768px) {
  .trend-grid { grid-template-columns: repeat(2, 1fr); }
}
</style>
