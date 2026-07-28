<script setup>
// 多维聚合页 — 按维度聚合查看利润（只读视图）
// 职责：
//   切换维度（platform / shop_id / currency）查看聚合后的订单数、营收、成本、利润、利润率、实际到账
// 数据来源：已核算入库的 profit_report，本页不触发核算
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { aggregateProfit } from '@/api/accounting'
import EChartsWrapper from '@/components/EChartsWrapper.vue'
import { usePageState } from '@/composables/usePageState'
import { useAccountingStore } from '@/store/accounting'

const router = useRouter()
// 页面状态保持（核算模式、周期、日期范围、聚合维度切换页面后自动恢复）
const { loadField, saveField } = usePageState('ProfitAggregate')
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

// 多维聚合切换：当前维度 + 数据
const aggDimension = ref(loadField('aggDimension', 'platform'))
const aggData = ref([])
const aggLoading = ref(false)
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

// 聚合表格维度标签映射
function aggDimensionLabel(dim) {
  return { platform: '平台', shop_id: '店铺', currency: '币种' }[dim] || dim
}

// 切换模式时保留另一模式的周期值（切回来还能用），只清空当前展示数据
function handleModeChange() {
  aggData.value = []
  hasLoaded.value = false
  // 若新模式已有有效周期，自动加载
  const params = buildPeriodParams()
  if (params) {
    loadAggregate()
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

// 加载聚合数据
async function loadAggregate() {
  const params = buildPeriodParams()
  if (!params) {
    aggData.value = []
    hasLoaded.value = false
    return
  }
  aggLoading.value = true
  try {
    const res = await aggregateProfit({ ...params, dimension: aggDimension.value })
    aggData.value = res.data || []
    hasLoaded.value = true
  } catch (e) {
    // 错误已由请求拦截器统一处理
  } finally {
    aggLoading.value = false
  }
}

// 切换聚合维度时重新加载聚合数据
async function handleAggDimensionChange() {
  await loadAggregate()
}

// 跳转到利润明细页执行核算
function goToCalculate() {
  router.push('/accounting/profit-detail')
}

// 是否无数据（已加载但聚合结果为空）→ 显示未核算提示
const noData = computed(() => {
  if (!hasLoaded.value) return false
  return aggData.value.length === 0
})

// KPI 汇总：对当前 aggData 求和（总营收 / 总成本 / 总利润 / 总到账）
const kpiTotals = computed(() => {
  const totals = { total_revenue: 0, total_cost: 0, total_profit: 0, actual_received: 0 }
  for (const row of aggData.value) {
    totals.total_revenue += Number(row.total_revenue) || 0
    totals.total_cost += Number(row.total_cost) || 0
    totals.total_profit += Number(row.total_profit) || 0
    totals.actual_received += Number(row.actual_received) || 0
  }
  return totals
})

// 利润占比分布饼图配置（数据来源：当前 aggData，取 dim_value 和 total_profit）
const profitShareOption = computed(() => {
  if (!aggData.value.length) return null
  return {
    title: { text: '利润占比分布', left: 'left', textStyle: { fontSize: 14 } },
    tooltip: { trigger: 'item', formatter: '{b}: ¥{c} ({d}%)' },
    legend: { bottom: 0, type: 'scroll' },
    series: [
      {
        name: '利润占比',
        type: 'pie',
        radius: ['40%', '70%'],
        avoidLabelOverlap: false,
        itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 2 },
        label: { show: true, formatter: '{b}\n{d}%' },
        data: aggData.value.map(item => ({
          name: String(item.dim_value),
          value: Number(item.total_profit)
        }))
      }
    ]
  }
})

// 周期变化时自动加载（仅 MONTH 模式）
watch(period, () => {
  if (calcMode.value === 'MONTH' && period.value) {
    loadAggregate()
  }
})

// 日期范围变化时自动加载（仅 RANGE 模式）
watch(dateRange, () => {
  if (calcMode.value === 'RANGE' && dateRange.value && dateRange.value.length === 2) {
    loadAggregate()
  }
})

// 监听「利润明细」页核算成功：同步本次核算的周期到本页
// 同步后 calcMode/period/dateRange 变化会触发上面的 watch → loadAggregate，无需手动调用
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
watch(aggDimension, v => saveField('aggDimension', v))

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
  // 初始化时加载聚合数据
  loadAggregate()
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
          <div style="margin-top: 4px">请先在【利润明细】页执行核算，核算完成后即可查看多维聚合分析。</div>
          <el-button type="primary" size="small" style="margin-top: 8px" @click="goToCalculate">
            <el-icon><Right /></el-icon> 前往利润明细页
          </el-button>
        </template>
      </el-alert>
    </div>

    <!-- 顶部 KPI 汇总：总营收 / 总成本 / 总利润 / 总到账 -->
    <div v-if="!noData && aggData.length" class="page-card mb-16">
      <el-row :gutter="16">
        <el-col :xs="12" :sm="12" :md="6">
          <div class="kpi-card">
            <div class="kpi-label">总营收 (CNY)</div>
            <div class="kpi-value">¥ {{ formatMoney(kpiTotals.total_revenue) }}</div>
          </div>
        </el-col>
        <el-col :xs="12" :sm="12" :md="6">
          <div class="kpi-card">
            <div class="kpi-label">总成本 (CNY)</div>
            <div class="kpi-value">¥ {{ formatMoney(kpiTotals.total_cost) }}</div>
          </div>
        </el-col>
        <el-col :xs="12" :sm="12" :md="6">
          <div class="kpi-card">
            <div class="kpi-label">总利润 (CNY)</div>
            <div class="kpi-value" :style="{ color: kpiTotals.total_profit >= 0 ? '#67c23a' : '#f56c6c' }">¥ {{ formatMoney(kpiTotals.total_profit) }}</div>
          </div>
        </el-col>
        <el-col :xs="12" :sm="12" :md="6">
          <div class="kpi-card">
            <div class="kpi-label">总到账 (CNY)</div>
            <div class="kpi-value">¥ {{ formatMoney(kpiTotals.actual_received) }}</div>
          </div>
        </el-col>
      </el-row>
    </div>

    <!-- 利润占比饼图（独占一行，全宽居中展示） -->
    <div v-if="!noData && aggData.length" class="page-card mb-16">
      <EChartsWrapper :option="profitShareOption" height="380px" />
    </div>

    <!-- 多维聚合分析：维度切换 + 聚合数据表（独占一行，全宽展示） -->
    <div class="page-card mb-16" v-loading="aggLoading" v-if="!noData">
      <div class="section-title">
        <span>多维聚合分析</span>
      </div>
      <el-radio-group v-model="aggDimension" size="small" @change="handleAggDimensionChange" style="margin-bottom: 12px">
        <el-radio-button value="platform">按平台</el-radio-button>
        <el-radio-button value="shop_id">按店铺</el-radio-button>
        <el-radio-button value="currency">按币种</el-radio-button>
      </el-radio-group>
      <el-empty v-if="!aggData.length" :description="`暂无${aggDimensionLabel(aggDimension)}聚合数据`" :image-size="60" />
      <el-table v-else :data="aggData" border stripe size="small">
        <el-table-column type="index" label="#" width="50" align="center" />
        <el-table-column label="维度值" min-width="140">
          <template #default="{ row }">
            <el-tag size="small" type="info">{{ aggDimensionLabel(aggDimension) }}</el-tag>
            <span style="margin-left: 8px; font-weight: 600">{{ row.dim_value }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="order_count" label="订单数" width="100" align="right" />
        <el-table-column label="营收 (CNY)" width="150" align="right">
          <template #default="{ row }">¥ {{ formatMoney(row.total_revenue) }}</template>
        </el-table-column>
        <el-table-column label="成本 (CNY)" width="150" align="right">
          <template #default="{ row }">¥ {{ formatMoney(row.total_cost) }}</template>
        </el-table-column>
        <el-table-column label="利润 (CNY)" width="150" align="right">
          <template #default="{ row }">
            <span :style="{ color: Number(row.total_profit) >= 0 ? '#67c23a' : '#f56c6c', fontWeight: 600 }">
              ¥ {{ formatMoney(row.total_profit) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="利润率" width="110" align="right">
          <template #default="{ row }">
            <span :style="{ color: Number(row.total_revenue) > 0 && (Number(row.total_profit) / Number(row.total_revenue)) >= 0 ? '#67c23a' : '#f56c6c' }">
              {{ Number(row.total_revenue) > 0 ? formatRate(Number(row.total_profit) / Number(row.total_revenue)) : '-' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="实际到账 (CNY)" width="150" align="right">
          <template #default="{ row }">¥ {{ formatMoney(row.actual_received) }}</template>
        </el-table-column>
      </el-table>
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
.badge-a { background: #409eff; }
.kpi-card { text-align: center; padding: 16px; background: #f7f9fc; border-radius: 6px; }
.kpi-card .kpi-label { font-size: 13px; color: #909399; margin-bottom: 8px; }
.kpi-card .kpi-value { font-size: 22px; font-weight: 600; color: #303133; }
</style>
