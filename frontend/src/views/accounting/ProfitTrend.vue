<script setup>
// 趋势分析页 — 多月趋势 + 同比对比（只读视图）
// 职责：
//   1. 展示近 N 个月（6/12/24）的营收/成本/利润折线趋势
//   2. 展示当期 vs 去年同期的同比对比表
// 数据来源：已核算入库的 profit_report，本页不触发核算
// 注意：trend 接口仅支持 MONTH 模式（period=YYYYMM），因此本页只提供月份选择器
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { getProfitTrend } from '@/api/accounting'
import EChartsWrapper from '@/components/EChartsWrapper.vue'
import { usePageState } from '@/composables/usePageState'
import { useAccountingStore } from '@/store/accounting'

const router = useRouter()
// 页面状态保持（月份周期、趋势月数切换页面后自动恢复）
const { loadField, saveField } = usePageState('ProfitTrend')
// 业财核算共享状态：监听「利润明细」页核算成功后同步周期
const accountingStore = useAccountingStore()

// 月份周期：默认上个月（YYYYMM）
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
// 趋势月数：6 / 12 / 24
const trendMonths = ref(loadField('trendMonths', 12))

// 趋势数据
const trendData = ref(null)
const trendLoading = ref(false)
// 是否已尝试加载（用于区分"加载中"与"无数据"两种空状态）
const hasLoaded = ref(false)

// 金额格式化
function formatMoney(num) {
  if (num === null || num === undefined || num === '') return '-'
  const n = Number(num)
  if (isNaN(n)) return '-'
  return n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

// 加载趋势数据（仅 MONTH 模式）
async function loadTrend() {
  if (!period.value) {
    trendData.value = null
    hasLoaded.value = false
    return
  }
  trendLoading.value = true
  try {
    const res = await getProfitTrend({ period: period.value, months: trendMonths.value })
    trendData.value = res.data
    hasLoaded.value = true
  } catch (e) {
    // 错误已由请求拦截器统一处理
  } finally {
    trendLoading.value = false
  }
}

// 切换趋势月数
async function handleTrendMonthsChange() {
  await loadTrend()
}

// 跳转到利润明细页执行核算
function goToCalculate() {
  router.push('/accounting/profit-detail')
}

// 是否无数据（已加载但趋势为空）→ 显示未核算提示
const noData = computed(() => {
  if (!hasLoaded.value) return false
  const td = trendData.value
  return !td || !td.monthlyTrend || td.monthlyTrend.length === 0
})

// 多月趋势折线图配置
const trendChartOption = computed(() => {
  const td = trendData.value
  if (!td || !td.monthlyTrend || !td.monthlyTrend.length) return null
  const months = td.monthlyTrend.map(m => {
    // YYYYMM → YYYY-MM 显示
    const p = String(m.period)
    return p.length === 6 ? `${p.slice(0, 4)}-${p.slice(4, 6)}` : p
  })
  const toNum = v => Number(v) || 0
  return {
    title: { text: `近 ${td.monthlyTrend.length} 个月利润趋势`, left: 'left', textStyle: { fontSize: 14 } },
    tooltip: {
      trigger: 'axis',
      formatter: params => {
        let html = `${params[0].axisValue}<br/>`
        params.forEach(p => {
          html += `${p.marker} ${p.seriesName}: ¥ ${Number(p.value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}<br/>`
        })
        return html
      }
    },
    legend: { bottom: 0, data: ['营收', '成本', '利润'] },
    grid: { left: 60, right: 30, top: 50, bottom: 40 },
    xAxis: { type: 'category', data: months, axisLabel: { rotate: 30 } },
    yAxis: { type: 'value', name: '金额 (CNY)', axisLabel: { formatter: v => (v / 10000).toFixed(1) + '万' } },
    series: [
      {
        name: '营收', type: 'line', smooth: true, symbol: 'circle', symbolSize: 6,
        data: td.monthlyTrend.map(m => toNum(m.total_revenue)),
        itemStyle: { color: '#409eff' },
        areaStyle: { color: 'rgba(64,158,255,0.1)' }
      },
      {
        name: '成本', type: 'line', smooth: true, symbol: 'circle', symbolSize: 6,
        data: td.monthlyTrend.map(m => toNum(m.total_cost)),
        itemStyle: { color: '#e6a23c' }
      },
      {
        name: '利润', type: 'line', smooth: true, symbol: 'circle', symbolSize: 6,
        data: td.monthlyTrend.map(m => toNum(m.total_profit)),
        itemStyle: { color: '#67c23a' },
        lineStyle: { width: 3 },
        markPoint: {
          data: [
            { type: 'max', name: '最大值' },
            { type: 'min', name: '最小值' }
          ]
        }
      }
    ]
  }
})

// 同比对比数据格式化
const yoyDisplay = computed(() => {
  const yoy = trendData.value?.yoy
  if (!yoy || !yoy.available) return null
  const fmtPct = v => v === null || v === undefined ? '—' : (Number(v) >= 0 ? '+' : '') + Number(v).toFixed(2) + '%'
  const fmtMoney = v => '¥ ' + (Number(v) || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  const fmtRate = v => (Number(v) * 100).toFixed(2) + '%'
  return {
    currentPeriod: yoy.currentPeriod,
    lastYearPeriod: yoy.lastYearPeriod,
    current: {
      revenue: fmtMoney(yoy.current.revenue),
      cost: fmtMoney(yoy.current.cost),
      profit: fmtMoney(yoy.current.profit),
      profitRate: fmtRate(yoy.current.profitRate)
    },
    lastYear: {
      revenue: fmtMoney(yoy.lastYear.revenue),
      cost: fmtMoney(yoy.lastYear.cost),
      profit: fmtMoney(yoy.lastYear.profit),
      profitRate: fmtRate(yoy.lastYear.profitRate)
    },
    revenueYoy: { text: fmtPct(yoy.revenueYoy), type: Number(yoy.revenueYoy) >= 0 ? 'success' : 'danger' },
    costYoy: { text: fmtPct(yoy.costYoy), type: Number(yoy.costYoy) >= 0 ? 'danger' : 'success' },
    profitYoy: { text: fmtPct(yoy.profitYoy), type: Number(yoy.profitYoy) >= 0 ? 'success' : 'danger' }
  }
})

// 周期变化时自动加载趋势
watch(period, () => {
  if (period.value) {
    loadTrend()
  } else {
    trendData.value = null
    hasLoaded.value = false
  }
})

// 监听「利润明细」页核算成功：同步本次核算的月份到本页
// trend 接口仅支持 MONTH 模式，RANGE 模式核算不触发同步（本页也无法切换为 RANGE）
watch(() => accountingStore.calcVersion, (v) => {
  if (v > 0 && accountingStore.lastCalcMode === 'MONTH' && accountingStore.lastCalcPeriod) {
    period.value = accountingStore.lastCalcPeriod
    // period 变化会触发上面的 watch → loadTrend，无需手动调用
  }
})

/* ==================== 页面状态持久化 ==================== */
watch(period, v => saveField('period', v))
watch(trendMonths, v => saveField('trendMonths', v))

onMounted(() => {
  // 若已发生过核算（从其他页面核算后切过来），同步最近核算的月份到本页
  // trend 接口仅支持 MONTH 模式，RANGE 模式核算不触发同步
  if (accountingStore.calcVersion > 0
      && accountingStore.lastCalcMode === 'MONTH'
      && accountingStore.lastCalcPeriod) {
    period.value = accountingStore.lastCalcPeriod
  }
  // period 已默认为上个月（或同步/恢复的月份），初始化时加载趋势
  loadTrend()
})
</script>

<template>
  <div>
    <!-- 顶部：月份选择 + 趋势月数切换 -->
    <div class="page-card mb-16">
      <el-form :inline="true">
        <el-form-item label="核算周期">
          <el-date-picker
            v-model="period"
            type="month"
            format="YYYYMM"
            value-format="YYYYMM"
            placeholder="请选择核算周期"
            style="width: 180px"
          />
        </el-form-item>
        <el-form-item label="趋势月数">
          <el-select v-model="trendMonths" size="default" style="width: 130px" @change="handleTrendMonthsChange">
            <el-option :value="6" label="近 6 个月" />
            <el-option :value="12" label="近 12 个月" />
            <el-option :value="24" label="近 24 个月" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-tooltip content="趋势接口仅支持月份模式；数据来源为已核算入库的 profit_report，如无数据请先在利润明细页执行核算" placement="top">
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
          <div style="margin-top: 4px">请先在【利润明细】页执行核算，核算完成后即可查看趋势分析。</div>
          <el-button type="primary" size="small" style="margin-top: 8px" @click="goToCalculate">
            <el-icon><Right /></el-icon> 前往利润明细页
          </el-button>
        </template>
      </el-alert>
    </div>

    <!-- A 多月趋势折线图（独占一行，全宽展示） -->
    <div class="page-card mb-16" v-loading="trendLoading" v-if="!noData">
      <div class="section-title">
        <span>多月趋势</span>
      </div>
      <el-empty v-if="!trendChartOption && !trendLoading" description="暂无趋势数据" :image-size="60" />
      <EChartsWrapper v-else-if="trendChartOption" :option="trendChartOption" height="420px" />
    </div>

    <!-- B 同比对比表（独占一行，全宽展示） -->
    <div class="page-card mb-16" v-loading="trendLoading" v-if="!noData">
      <div class="section-title">
        <span>同比对比</span>
        <el-tag v-if="yoyDisplay" size="small" type="info" style="margin-left: 12px">
          {{ yoyDisplay.lastYearPeriod }} → {{ yoyDisplay.currentPeriod }}
        </el-tag>
      </div>
      <template v-if="yoyDisplay">
        <el-table :data="[
          { name: '营收', current: yoyDisplay.current.revenue, lastYear: yoyDisplay.lastYear.revenue, yoy: yoyDisplay.revenueYoy },
          { name: '成本', current: yoyDisplay.current.cost, lastYear: yoyDisplay.lastYear.cost, yoy: yoyDisplay.costYoy },
          { name: '利润', current: yoyDisplay.current.profit, lastYear: yoyDisplay.lastYear.profit, yoy: yoyDisplay.profitYoy },
          { name: '利润率', current: yoyDisplay.current.profitRate, lastYear: yoyDisplay.lastYear.profitRate, yoy: { text: '—', type: 'info' } }
        ]" border size="small">
          <el-table-column prop="name" label="指标" width="160" />
          <el-table-column label="去年同期" align="right">
            <template #default="{ row }">{{ row.lastYear }}</template>
          </el-table-column>
          <el-table-column label="当期" align="right">
            <template #default="{ row }">{{ row.current }}</template>
          </el-table-column>
          <el-table-column label="同比" width="160" align="center">
            <template #default="{ row }">
              <el-tag :type="row.yoy.type" size="small">{{ row.yoy.text }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
        <el-alert
          v-if="yoyDisplay.profitYoy && Number(yoyDisplay.profitYoy.text) < 0"
          type="warning" :closable="false" style="margin-top: 12px"
          :title="`利润同比 ${yoyDisplay.profitYoy.text}，建议核查成本上升或营收下降的具体原因`"
        />
      </template>
      <el-empty v-else
                :description="trendData?.yoy?.reason || '去年同期无数据，无法计算同比'"
                :image-size="60" />
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
/* 区块字母徽章（与 ProfitDiagnosis 一致） */
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
</style>
