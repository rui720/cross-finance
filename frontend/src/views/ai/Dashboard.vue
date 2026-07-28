<script setup>
// 经营全景驾驶舱页：周期选择、4个统计卡片、3个图表（趋势/饼图/柱状图）
// 全部数据来自后端 /accounting/profit/dashboard 接口（基于已核算入库的 profit_report）
// 注意：本页只读不触发核算，若当期无数据则提示用户前往「利润明细」页核算
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { getDashboard } from '@/api/accounting'
import { usePageState } from '@/composables/usePageState'
import EChartsWrapper from '@/components/EChartsWrapper.vue'

const router = useRouter()

// 页面状态保持：切换页面后自动恢复上次选择的周期
const { loadField, saveField } = usePageState('Dashboard')

// 周期选择：默认上个月（YYYYMM）
function getLastMonthPeriod() {
  const now = new Date()
  const year = now.getFullYear()
  const month = now.getMonth() // 0-11，当前月=month+1，上个月=month
  if (month === 0) {
    return `${year - 1}12`
  }
  return `${year}${String(month).padStart(2, '0')}`
}
// el-date-picker 的 month 类型用 YYYYMM 作为 value-format，与后端一致
const period = ref(loadField('period', getLastMonthPeriod()))

// 后端返回的聚合数据
const dashboardData = ref(null)
const loading = ref(false)

// 金额格式化：保留2位小数，千分位
function formatMoney(num) {
  if (num === null || num === undefined || num === '') return '-'
  const n = Number(num)
  if (isNaN(n)) return '-'
  return n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

// 利润率格式化（后端返回 0-1 的小数 → xx.xx%）
function formatRate(num) {
  if (num === null || num === undefined || num === '') return '-'
  const n = Number(num)
  if (isNaN(n)) return '-'
  const pct = n <= 1 ? n * 100 : n
  return pct.toFixed(2) + '%'
}

// YYYYMM → "YYYY年MM月" 用于图表横轴与提示
function formatPeriodLabel(p) {
  if (!p || p.length !== 6) return p || ''
  return `${p.substring(0, 4)}年${p.substring(4, 6)}月`
}

// 摘要卡片数据
const stat = computed(() => {
  const s = dashboardData.value?.summary
  if (!s) {
    return { orderCount: 0, revenue: 0, cost: 0, profit: 0, profitRate: 0 }
  }
  return {
    orderCount: s.orderCount || 0,
    revenue: Number(s.totalRevenue || 0),
    cost: Number(s.totalCost || 0),
    profit: Number(s.totalProfit || 0),
    profitRate: Number(s.profitRate || 0)
  }
})

// 是否无数据（订单数为 0 视为当期未核算）
const isEmpty = computed(() => stat.value.orderCount === 0)

// 收入成本利润趋势图（折线图，近12个月）
const trendOption = computed(() => {
  const rows = dashboardData.value?.monthlyTrend || []
  const labels = rows.map(r => formatPeriodLabel(r.period))
  const revenues = rows.map(r => Number(r.total_revenue || 0))
  const costs = rows.map(r => Number(r.total_cost || 0))
  const profits = rows.map(r => Number(r.total_profit || 0))
  return {
    title: { text: '收入成本利润趋势（近12个月）', left: 'left', textStyle: { fontSize: 14 } },
    tooltip: { trigger: 'axis' },
    legend: { data: ['收入', '成本', '利润'], top: 0, right: 0 },
    grid: { left: 60, right: 20, top: 50, bottom: 40 },
    xAxis: { type: 'category', data: labels, axisLabel: { rotate: 30 } },
    yAxis: { type: 'value', name: '金额(CNY)' },
    series: [
      { name: '收入', type: 'line', smooth: true, data: revenues, itemStyle: { color: '#67c23a' } },
      { name: '成本', type: 'line', smooth: true, data: costs, itemStyle: { color: '#f56c6c' } },
      { name: '利润', type: 'line', smooth: true, data: profits, itemStyle: { color: '#409eff' } }
    ]
  }
})

// 各平台利润占比（饼图）
const platformPieOption = computed(() => {
  const list = dashboardData.value?.byPlatform || []
  if (list.length === 0) {
    return {
      title: { text: '各平台利润占比', left: 'left', textStyle: { fontSize: 14 } },
      graphic: { type: 'text', left: 'center', top: 'middle', style: { text: '暂无数据', fill: '#909399' } }
    }
  }
  return {
    title: { text: '各平台利润占比', left: 'left', textStyle: { fontSize: 14 } },
    tooltip: { trigger: 'item', formatter: '{a} <br/>{b}: ¥{c} ({d}%)' },
    legend: { orient: 'vertical', left: 'left', top: 'middle' },
    series: [
      {
        name: '平台利润',
        type: 'pie',
        radius: ['40%', '70%'],
        avoidLabelOverlap: false,
        itemStyle: { borderRadius: 8, borderColor: '#fff', borderWidth: 2 },
        label: { show: false, position: 'center' },
        emphasis: { label: { show: true, fontSize: 18, fontWeight: 'bold' } },
        labelLine: { show: false },
        data: list.map(p => ({ value: Number(p.profit || 0), name: p.platform }))
      }
    ]
  }
})

// 各币种收入分布（柱状图，按 CNY 金额排序）
const currencyBarOption = computed(() => {
  const list = dashboardData.value?.byCurrency || []
  if (list.length === 0) {
    return {
      title: { text: '各币种收入分布', left: 'left', textStyle: { fontSize: 14 } },
      graphic: { type: 'text', left: 'center', top: 'middle', style: { text: '暂无数据', fill: '#909399' } }
    }
  }
  return {
    title: { text: '各币种收入分布（按 CNY 金额）', left: 'left', textStyle: { fontSize: 14 } },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: params => {
        const idx = params[0].dataIndex
        const item = list[idx]
        return `${item.currency}<br/>原币金额: ${formatMoney(item.original_amount)}<br/>折算 CNY: ¥${formatMoney(item.cny_amount)}`
      }
    },
    legend: { data: ['折算 CNY'], top: 0, right: 0 },
    grid: { left: 60, right: 20, top: 50, bottom: 40 },
    xAxis: { type: 'category', data: list.map(r => r.currency) },
    yAxis: { type: 'value', name: '金额(CNY)' },
    series: [
      {
        name: '折算 CNY',
        type: 'bar',
        barWidth: '50%',
        itemStyle: { color: '#409eff', borderRadius: [4, 4, 0, 0] },
        data: list.map(r => Number(r.cny_amount || 0))
      }
    ]
  }
})

// 加载驾驶舱数据
async function loadData() {
  if (!period.value) return
  loading.value = true
  try {
    const res = await getDashboard(period.value)
    dashboardData.value = res.data
  } catch (e) {
    // 错误已由请求拦截器统一处理
  } finally {
    loading.value = false
  }
}

// 跳转到利润明细页（用户可在此触发核算）
function goToProfitDetail() {
  router.push('/accounting/profit-detail')
}

// 周期变化时重新加载
watch(period, () => {
  saveField('period', period.value)
  loadData()
})

onMounted(() => {
  loadData()
})
</script>

<template>
  <div v-loading="loading">
    <!-- 顶部：周期选择 -->
    <div class="page-card mb-16">
      <el-form :inline="true">
        <el-form-item label="统计周期">
          <el-date-picker
            v-model="period"
            type="month"
            format="YYYYMM"
            value-format="YYYYMM"
            placeholder="请选择统计周期"
            style="width: 200px"
          />
        </el-form-item>
        <el-form-item>
          <el-text type="info" size="small">
            数据来源于已核算入库的利润报表，如周期无数据请前往「利润明细」页触发核算
          </el-text>
        </el-form-item>
      </el-form>
    </div>

    <!-- 无数据提示 -->
    <el-alert
      v-if="!loading && isEmpty"
      type="warning"
      :closable="false"
      style="margin-bottom: 16px"
      title="当前周期暂无利润数据"
    >
      <template #default>
        <div style="line-height: 1.6">
          所选周期 <b>{{ formatPeriodLabel(period) }}</b> 还没有核算入库的利润数据。
          请前往「利润明细」页面触发核算后再返回查看。
          <el-button type="primary" link @click="goToProfitDetail">前往利润明细 →</el-button>
        </div>
      </template>
    </el-alert>

    <!-- 4个统计卡片 -->
    <div class="stat-row mb-16">
      <el-card shadow="hover" class="stat-card">
        <div class="stat-label">本期订单数</div>
        <div class="stat-value" style="color: #909399">{{ stat.orderCount }} 笔</div>
      </el-card>
      <el-card shadow="hover" class="stat-card">
        <div class="stat-label">本期收入 (CNY)</div>
        <div class="stat-value" style="color: #67c23a">¥ {{ formatMoney(stat.revenue) }}</div>
      </el-card>
      <el-card shadow="hover" class="stat-card">
        <div class="stat-label">本期成本 (CNY)</div>
        <div class="stat-value" style="color: #f56c6c">¥ {{ formatMoney(stat.cost) }}</div>
      </el-card>
      <el-card shadow="hover" class="stat-card">
        <div class="stat-label">本期利润 (CNY)</div>
        <div class="stat-value" :style="{ color: stat.profit >= 0 ? '#409eff' : '#f56c6c' }">
          ¥ {{ formatMoney(stat.profit) }}
        </div>
      </el-card>
      <el-card shadow="hover" class="stat-card">
        <div class="stat-label">利润率</div>
        <div class="stat-value" :style="{ color: stat.profitRate >= 0.05 ? '#67c23a' : '#e6a23c' }">
          {{ formatRate(stat.profitRate) }}
        </div>
      </el-card>
    </div>

    <!-- 图表区：每行2个图表 -->
    <el-row :gutter="16">
      <el-col :span="12">
        <div class="page-card mb-16">
          <EChartsWrapper :option="trendOption" height="360px" />
        </div>
      </el-col>
      <el-col :span="12">
        <div class="page-card mb-16">
          <EChartsWrapper :option="platformPieOption" height="360px" />
        </div>
      </el-col>
      <el-col :span="12">
        <div class="page-card mb-16">
          <EChartsWrapper :option="currencyBarOption" height="360px" />
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.stat-row {
  display: flex;
  gap: 16px;
}
.stat-card {
  flex: 1;
}
.stat-label {
  font-size: 13px;
  color: #909399;
  margin-bottom: 8px;
}
.stat-value {
  font-size: 22px;
  font-weight: 600;
}

@media (max-width: 768px) {
  .stat-row { flex-wrap: wrap; }
  .stat-card { flex: 1 1 45%; }
}
</style>
