<script setup>
// 成本结构页 — 成本构成分析 + 对账对比（只读视图）
// 职责：
//   1. 展示当期成本结构拆分（平台费 / 公共分摊 / 直接成本 / 总成本）+ 占比饼图
//   2. 展示账面利润 vs 实际到账金额对比（到账差异 / 已匹配 / 未到账 / 差异订单）
// 数据来源：已核算入库的 profit_report，本页不触发核算
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { getCostStructure, getReconcileSummary } from '@/api/accounting'
import EChartsWrapper from '@/components/EChartsWrapper.vue'
import { usePageState } from '@/composables/usePageState'
import { useAccountingStore } from '@/store/accounting'

const router = useRouter()
// 页面状态保持（核算模式、周期、日期范围切换页面后自动恢复）
const { loadField, saveField } = usePageState('CostStructure')
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

// 成本结构汇总（平台费 / 公共分摊 / 直接成本）
const costStructure = ref(null)
const costStructureLoading = ref(false)
// 对账汇总（账面利润 vs 实际到账）
const reconcileSummary = ref(null)
const reconcileLoading = ref(false)
// 是否已尝试加载（用于区分"加载中"与"无数据"两种空状态）
const hasLoaded = ref(false)

// 金额格式化
function formatMoney(num) {
  if (num === null || num === undefined || num === '') return '-'
  const n = Number(num)
  if (isNaN(n)) return '-'
  return n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

// 切换模式时保留另一模式的周期值（切回来还能用），只清空当前展示数据
function handleModeChange() {
  costStructure.value = null
  reconcileSummary.value = null
  hasLoaded.value = false
  // 若新模式已有有效周期，自动加载
  const params = buildPeriodParams()
  if (params) {
    loadData()
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

// 加载成本结构 + 对账对比（并行）
async function loadData() {
  const params = buildPeriodParams()
  if (!params) {
    costStructure.value = null
    reconcileSummary.value = null
    hasLoaded.value = false
    return
  }
  costStructureLoading.value = true
  reconcileLoading.value = true
  try {
    const [csRes, rsRes] = await Promise.all([
      getCostStructure(params),
      getReconcileSummary(params)
    ])
    costStructure.value = csRes.data
    reconcileSummary.value = rsRes.data
    hasLoaded.value = true
  } catch (e) {
    // 错误已由请求拦截器统一处理
  } finally {
    costStructureLoading.value = false
    reconcileLoading.value = false
  }
}

// 跳转到利润明细页执行核算
function goToCalculate() {
  router.push('/accounting/profit-detail')
}

// 是否无数据（已加载但成本结构和对账均为空）→ 显示未核算提示
const noData = computed(() => {
  if (!hasLoaded.value) return false
  const csEmpty = !costStructure.value || Number(costStructure.value.total_cost) === 0
  const rsEmpty = !reconcileSummary.value
  return csEmpty && rsEmpty
})

// 成本结构占比饼图配置
const costStructureOption = computed(() => {
  const cs = costStructure.value
  if (!cs) return null
  const total = Number(cs.total_cost) || 0
  if (total === 0) {
    return { title: { text: '成本结构', left: 'left' }, graphic: { type: 'text', left: 'center', top: 'middle', style: { text: '暂无数据', fill: '#909399' } } }
  }
  return {
    title: { text: '成本结构占比', left: 'left', textStyle: { fontSize: 14 } },
    tooltip: { trigger: 'item', formatter: '{b}: ¥{c} ({d}%)' },
    legend: { bottom: 0 },
    series: [
      {
        name: '成本结构',
        type: 'pie',
        radius: ['40%', '70%'],
        avoidLabelOverlap: false,
        itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 2 },
        label: { show: true, formatter: '{b}\n{d}%' },
        data: [
          { value: Number(cs.total_fee) || 0, name: '平台费', itemStyle: { color: '#409eff' } },
          { value: Number(cs.total_shared) || 0, name: '公共分摊', itemStyle: { color: '#e6a23c' } },
          { value: Number(cs.total_direct) || 0, name: '直接成本', itemStyle: { color: '#67c23a' } }
        ]
      }
    ]
  }
})

// 周期变化时自动加载（仅 MONTH 模式）
watch(period, () => {
  if (calcMode.value === 'MONTH' && period.value) {
    loadData()
  }
})

// 日期范围变化时自动加载（仅 RANGE 模式）
watch(dateRange, () => {
  if (calcMode.value === 'RANGE' && dateRange.value && dateRange.value.length === 2) {
    loadData()
  }
})

// 监听「利润明细」页核算成功：同步本次核算的周期到本页
// 同步后 calcMode/period/dateRange 变化会触发上面的 watch → loadData，无需手动调用
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
  // 初始化时加载成本结构和对账数据
  loadData()
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
          <div style="margin-top: 4px">请先在【利润明细】页执行核算，核算完成后即可查看成本结构与对账对比。</div>
          <el-button type="primary" size="small" style="margin-top: 8px" @click="goToCalculate">
            <el-icon><Right /></el-icon> 前往利润明细页
          </el-button>
        </template>
      </el-alert>
    </div>

    <!-- 顶部 KPI 行：总成本 / 平台费 / 公共分摊 / 直接成本 / 到账差异 -->
    <div class="page-card mb-16" v-if="!noData" v-loading="costStructureLoading || reconcileLoading">
      <div class="kpi-row">
        <div class="kpi-card">
          <div class="kpi-label">总成本</div>
          <div class="kpi-value" style="color: #f56c6c">¥ {{ formatMoney(costStructure?.total_cost) }}</div>
        </div>
        <div class="kpi-card">
          <div class="kpi-label">平台费</div>
          <div class="kpi-value" style="color: #409eff">¥ {{ formatMoney(costStructure?.total_fee) }}</div>
        </div>
        <div class="kpi-card">
          <div class="kpi-label">公共分摊</div>
          <div class="kpi-value" style="color: #e6a23c">¥ {{ formatMoney(costStructure?.total_shared) }}</div>
        </div>
        <div class="kpi-card">
          <div class="kpi-label">直接成本</div>
          <div class="kpi-value" style="color: #67c23a">¥ {{ formatMoney(costStructure?.total_direct) }}</div>
        </div>
        <div class="kpi-card">
          <div class="kpi-label">到账差异</div>
          <div class="kpi-value"
               :style="{ color: (Number(reconcileSummary?.total_actual_received) || 0) - (Number(reconcileSummary?.total_book_profit) || 0) >= 0 ? '#67c23a' : '#f56c6c' }">
            ¥ {{ formatMoney(reconcileSummary ? (Number(reconcileSummary.total_actual_received) || 0) - (Number(reconcileSummary.total_book_profit) || 0) : null) }}
          </div>
        </div>
      </div>
    </div>

    <!-- 成本结构拆分（平台费 / 公共分摊 / 直接成本） -->
    <div class="page-card mb-16" v-loading="costStructureLoading" v-if="!noData">
      <div class="section-title">
        <span>成本结构拆分</span>
      </div>
      <el-empty v-if="!costStructure || Number(costStructure.total_cost) === 0"
                description="选择核算周期后展示成本结构" :image-size="60" />
      <template v-else>
        <!-- 饼图：独占一行，全宽展示 -->
        <EChartsWrapper v-if="costStructureOption" :option="costStructureOption" height="380px" />
        <!-- 成本明细 descriptions：独占一行，2 列横排铺开 -->
        <el-descriptions :column="2" border size="small" style="margin-top: 16px">
          <el-descriptions-item label="平台费（直接归属）">
            <span style="color: #409eff; font-weight: 600">¥ {{ formatMoney(costStructure.total_fee) }}</span>
            <span style="margin-left: 12px; color: #909399">
              占比 {{ ((Number(costStructure.total_fee) || 0) / (Number(costStructure.total_cost) || 1) * 100).toFixed(1) }}%
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="公共分摊（无订单号额外费用）">
            <span style="color: #e6a23c; font-weight: 600">¥ {{ formatMoney(costStructure.total_shared) }}</span>
            <span style="margin-left: 12px; color: #909399">
              占比 {{ ((Number(costStructure.total_shared) || 0) / (Number(costStructure.total_cost) || 1) * 100).toFixed(1) }}%
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="直接成本（按订单号归集）">
            <span style="color: #67c23a; font-weight: 600">¥ {{ formatMoney(costStructure.total_direct) }}</span>
            <span style="margin-left: 12px; color: #909399">
              占比 {{ ((Number(costStructure.total_direct) || 0) / (Number(costStructure.total_cost) || 1) * 100).toFixed(1) }}%
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="总成本">
            <span style="color: #f56c6c; font-weight: 700">¥ {{ formatMoney(costStructure.total_cost) }}</span>
          </el-descriptions-item>
        </el-descriptions>
        <el-alert
          v-if="(Number(costStructure.total_shared) || 0) / (Number(costStructure.total_cost) || 1) > 0.5"
          type="warning" :closable="false" style="margin-top: 12px"
          title="公共分摊占比超过 50%，建议核查无订单号的额外费用是否可归集到具体订单，以提升利润归因精度"
        />
      </template>
    </div>

    <!-- 账面利润 vs 实际到账利润 -->
    <div class="page-card mb-16" v-loading="reconcileLoading" v-if="!noData">
      <div class="section-title">
        <span>账面利润 vs 实际到账</span>
      </div>
      <el-empty v-if="!reconcileSummary" description="选择核算周期后展示对账对比" :image-size="60" />
      <template v-else>
        <div class="reconcile-grid">
          <div class="reconcile-item">
            <div class="reconcile-label">账面利润合计</div>
            <div class="reconcile-value" style="color: #409eff">¥ {{ formatMoney(reconcileSummary.total_book_profit) }}</div>
          </div>
          <div class="reconcile-item">
            <div class="reconcile-label">实际到账金额</div>
            <div class="reconcile-value" style="color: #67c23a">¥ {{ formatMoney(reconcileSummary.total_actual_received) }}</div>
          </div>
          <div class="reconcile-item">
            <div class="reconcile-label">到账差异</div>
            <div class="reconcile-value"
                 :style="{ color: (Number(reconcileSummary.total_actual_received) || 0) - (Number(reconcileSummary.total_book_profit) || 0) >= 0 ? '#67c23a' : '#f56c6c' }">
              ¥ {{ formatMoney((Number(reconcileSummary.total_actual_received) || 0) - (Number(reconcileSummary.total_book_profit) || 0)) }}
            </div>
          </div>
          <div class="reconcile-item">
            <div class="reconcile-label">已匹配订单</div>
            <div class="reconcile-value">{{ reconcileSummary.matched_count }} 笔</div>
          </div>
          <div class="reconcile-item">
            <div class="reconcile-label">未到账订单</div>
            <div class="reconcile-value" :style="{ color: (reconcileSummary.unreceived_count || 0) > 0 ? '#f56c6c' : '#67c23a' }">
              {{ reconcileSummary.unreceived_count }} 笔
            </div>
          </div>
          <div class="reconcile-item">
            <div class="reconcile-label">差异订单</div>
            <div class="reconcile-value" :style="{ color: (reconcileSummary.diff_count || 0) > 0 ? '#e6a23c' : '#67c23a' }">
              {{ reconcileSummary.diff_count }} 笔
            </div>
          </div>
        </div>
        <el-alert
          v-if="(reconcileSummary.unreceived_count || 0) > 0"
          type="warning" :closable="false" style="margin-top: 12px"
          :title="`有 ${reconcileSummary.unreceived_count} 笔订单未到账，建议前往「数据底座 → 银行流水对账」核查资金是否到账`"
        />
      </template>
    </div>
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
.badge-b { background: #67c23a; }
.badge-c { background: #e6a23c; }

/* 顶部 KPI 行：5 个指标卡横排（移动端自动换行为 2 列） */
.kpi-row {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
}
.kpi-card {
  flex: 1 1 0;
  min-width: 160px;
  text-align: center;
  padding: 16px;
  background: #f7f9fc;
  border-radius: 6px;
}
.kpi-card .kpi-label {
  font-size: 13px;
  color: #909399;
  margin-bottom: 8px;
}
.kpi-card .kpi-value {
  font-size: 22px;
  font-weight: 600;
  color: #303133;
}

/* 对账对比：6 列横排（移动端 2 列） */
.reconcile-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
}
.reconcile-item {
  flex: 1 1 180px;
  min-width: 180px;
  background: #f5f7fa;
  padding: 16px;
  border-radius: 4px;
  text-align: center;
}
.reconcile-label {
  font-size: 12px;
  color: #909399;
  margin-bottom: 8px;
}
.reconcile-value {
  font-size: 20px;
  font-weight: 600;
  color: #303133;
}
</style>
