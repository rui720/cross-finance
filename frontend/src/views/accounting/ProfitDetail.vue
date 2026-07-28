<script setup>
// 利润明细页 — 业财核算的统一核算入口
// 职责：
//   1. 触发核算（MONTH/RANGE 两种模式），核算前自动检查数据完整性
//   2. 展示利润明细表（带筛选/分页/排序/汇总行）
//   3. 导出 Excel
//   4. 自动补全缺失日期的汇率（autoFillExchangeRate）
// 其他子页面（趋势/成本/诊断/聚合）均为只读视图，不再触发核算
import { reactive, ref, computed, onMounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  calculateProfit,
  calculateProfitByRange,
  checkDataIntegrity,
  pageProfitDetail,
  exportProfitReport,
  autoFillExchangeRate
} from '@/api/accounting'
import { useUserStore } from '@/store'
import { useAccountingStore } from '@/store/accounting'
import { usePageState } from '@/composables/usePageState'
import { useRouter } from 'vue-router'

const userStore = useUserStore()
const router = useRouter()
// 业财核算跨页面共享状态：核算成功后通知其他子页面同步周期
const accountingStore = useAccountingStore()
// 核算按钮仅 ADMIN/FINANCE 可见
const canCalculate = computed(() => {
  const roles = userStore.roles || []
  return roles.some(r => ['ADMIN', 'FINANCE'].includes(r))
})

// 页面状态保持（核算模式、周期、日期范围、页号、表格筛选切换页面后自动恢复）
const { loadField, saveField } = usePageState('ProfitDetail')

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

// 完整性检查状态
const checking = ref(false)
const integrityResult = ref(null)
const integrityDialogVisible = ref(false)
// 用户在完整性弹窗中选择"仍然继续核算"后的回调
let pendingCalculateFn = null

// 明细表
const tableData = ref([])
const total = ref(0)
const loading = ref(false)
const page = reactive(loadField('page', { page: 1, size: 10 }))

// 表格筛选：平台 / 店铺 / 币种 / 对账状态（传后端，先筛选再分页）
const tableFilter = reactive(loadField('tableFilter', {
  platform: '',
  shopId: '',
  currency: '',
  reconcileStatus: '' // '' 全部 / 0 未对账 / 1 已完成 / 2 对账失败 / 3 未到账 / 4 不明入账
}))
// 表格排序
const tableSort = reactive({ prop: 'profitAmount', order: 'descending' })

// 表格汇总行（由后端返回当前筛选条件下的全量合计，覆盖所有页而非仅当前页）
const tableSummary = ref(null)

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

// 时间格式化（去掉 T 和毫秒）
function formatTime(t) {
  if (!t) return '-'
  return String(t).replace('T', ' ').split('.')[0]
}

// 切换模式时保留另一模式的周期值（切回来还能用），只清空当前展示数据
function handleModeChange() {
  tableData.value = []
  total.value = 0
  integrityResult.value = null
  // 若新模式已有有效周期，自动加载
  const params = buildPeriodParams()
  if (params) {
    loadData()
  }
}

/**
 * 构造查询所需的 period / startDate+endDate 参数。
 * <ul>
 *   <li>MONTH 模式：返回 { period: YYYYMM }</li>
 *   <li>RANGE 模式：返回 { startDate, endDate }</li>
 *   <li>无有效周期：返回 null（调用方应跳过请求）</li>
 * </ul>
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

// 表格展示数据（筛选已由后端完成，前端仅做排序）
const filteredTableData = computed(() => {
  let data = tableData.value
  // 排序
  if (tableSort.prop && tableSort.order) {
    const prop = tableSort.prop
    const dir = tableSort.order === 'ascending' ? 1 : -1
    data = [...data].sort((a, b) => {
      const va = a[prop] ?? 0
      const vb = b[prop] ?? 0
      if (typeof va === 'number' && typeof vb === 'number') return (va - vb) * dir
      return String(va).localeCompare(String(vb)) * dir
    })
  }
  return data
})

// 表格筛选下拉选项（由后端返回当前周期下的全量选项，不受筛选条件影响）
const platformOptions = ref([])
const shopOptions = ref([])
const currencyOptions = ref([])

// 重置表格筛选（清空条件并重新请求后端）
function resetTableFilter() {
  tableFilter.platform = ''
  tableFilter.shopId = ''
  tableFilter.currency = ''
  tableFilter.reconcileStatus = ''
  page.page = 1
  loadData()
}

// 筛选条件变化时回到第一页并重新请求后端
function handleFilterChange() {
  page.page = 1
  loadData()
}

// 表格排序变更
function handleSortChange({ prop, order }) {
  tableSort.prop = prop
  tableSort.order = order
}

// 表格底部汇总行（el-table 的 summary-method）
// 数据来自后端返回的全量汇总 tableSummary，覆盖所有筛选结果而非仅当前页
function getSummary({ columns, data }) {
  const sums = []
  const s = tableSummary.value
  columns.forEach((col, idx) => {
    if (idx === 0) {
      sums[idx] = '合计'
      return
    }
    switch (col.property) {
      case 'orderNo':
        sums[idx] = `${s?.orderCount ?? data.length} 笔`
        break
      case 'platformAmountCny':
        sums[idx] = '¥ ' + formatMoney(s?.totalBillAmount ?? 0)
        break
      case 'platformFeeCny':
        sums[idx] = '¥ ' + formatMoney(s?.totalPlatformFee ?? 0)
        break
      case 'transferFeeCny':
        sums[idx] = '¥ ' + formatMoney(data.reduce((a, r) => a + (Number(r.transferFeeCny) || 0), 0))
        break
      case 'bankReceivedCny':
        sums[idx] = '¥ ' + formatMoney(data.reduce((a, r) => a + (Number(r.bankReceivedCny) || 0), 0))
        break
      case 'reconcileDiff':
        sums[idx] = '¥ ' + formatMoney(data.reduce((a, r) => a + (Number(r.reconcileDiff) || 0), 0))
        break
      case 'sharedCost':
        sums[idx] = '¥ ' + formatMoney(s?.totalSharedCost ?? 0)
        break
      case 'directCost':
        sums[idx] = '¥ ' + formatMoney(s?.totalDirectCost ?? 0)
        break
      case 'costAmount':
        sums[idx] = '¥ ' + formatMoney(s?.totalCostAmount ?? 0)
        break
      case 'profitAmount':
        sums[idx] = '¥ ' + formatMoney(s?.totalProfitAmount ?? 0)
        break
      case 'profitRate':
        sums[idx] = formatRate(s?.profitRate ?? 0)
        break
      default:
        sums[idx] = ''
    }
  })
  return sums
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

// 触发核算（入口，根据模式分流）
async function handleCalculate() {
  if (calcMode.value === 'MONTH') {
    if (!period.value) {
      ElMessage.warning('请先选择核算周期')
      return
    }
    await doCalculateMonth()
  } else {
    // RANGE 模式：先做完整性检查
    if (!dateRange.value || dateRange.value.length !== 2) {
      ElMessage.warning('请先选择核算日期范围')
      return
    }
    const [startDate, endDate] = dateRange.value
    await doCalculateRange(startDate, endDate)
  }
}

// 导出当前查询条件的利润报表为 Excel
const exporting = ref(false)
async function handleExport() {
  // 至少需要选择核算周期或日期范围，避免误导全表
  if (calcMode.value === 'MONTH' && !period.value) {
    ElMessage.warning('请先选择核算周期')
    return
  }
  if (calcMode.value === 'RANGE' && (!dateRange.value || dateRange.value.length !== 2)) {
    ElMessage.warning('请先选择核算日期范围')
    return
  }
  exporting.value = true
  try {
    let params
    if (calcMode.value === 'MONTH') {
      params = { period: period.value }
    } else {
      params = { startDate: dateRange.value[0], endDate: dateRange.value[1] }
    }
    const res = await exportProfitReport(params)
    // 从响应头获取文件名（后端 URLEncode 处理过）
    const disposition = res.headers['content-disposition'] || ''
    let fileName = '利润报表.xlsx'
    const match = disposition.match(/filename\*=UTF-8''([^;]+)/i)
    if (match && match[1]) {
      fileName = decodeURIComponent(match[1])
    }
    // 创建 Blob 下载
    const blob = new Blob([res.data], {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
    })
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = fileName
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    window.URL.revokeObjectURL(url)
    ElMessage.success('导出成功')
  } catch (e) {
    console.error('导出失败', e)
    ElMessage.error('导出失败，请稍后重试')
  } finally {
    exporting.value = false
  }
}

// 月份模式核算
async function doCalculateMonth() {
  // 月份模式下也先做完整性检查（period YYYYMM → 当月起止日期）
  const { firstDay, lastDay } = periodToRange(period.value)
  checking.value = true
  try {
    const res = await checkDataIntegrity(firstDay, lastDay)
    integrityResult.value = res.data
    if (res.data?.blocking) {
      integrityDialogVisible.value = true
      return
    }
    if (res.data?.missingRanges?.length > 0 || res.data?.currencyRateMissing?.length > 0) {
      pendingCalculateFn = async () => {
        await invokeCalculateMonth()
      }
      integrityDialogVisible.value = true
      return
    }
    await invokeCalculateMonth()
  } catch (e) {
    // 错误已由请求拦截器统一处理
  } finally {
    checking.value = false
  }
}

// 实际调用月份核算接口
async function invokeCalculateMonth() {
  try {
    await calculateProfit(period.value)
    ElMessage.success('核算完成')
    // 通知其他子页面同步本次核算的周期
    accountingStore.notifyCalcSuccess('MONTH', period.value, [])
    loadData()
  } catch (e) {
    // 错误已由请求拦截器统一处理
  }
}

// 将 YYYYMM 转换为当月第一天和最后一天（yyyy-MM-dd）
function periodToRange(period) {
  const year = parseInt(period.substring(0, 4))
  const month = parseInt(period.substring(4, 6))
  const firstDay = `${period.substring(0, 4)}-${period.substring(4, 6)}-01`
  // 当月最后一天：下个月第 0 天
  const lastDate = new Date(year, month, 0)
  const lastDay = `${lastDate.getFullYear()}-${String(lastDate.getMonth() + 1).padStart(2, '0')}-${String(lastDate.getDate()).padStart(2, '0')}`
  return { firstDay, lastDay }
}

// 日期范围模式核算（含完整性检查）
async function doCalculateRange(startDate, endDate) {
  checking.value = true
  try {
    const res = await checkDataIntegrity(startDate, endDate)
    integrityResult.value = res.data
    if (res.data?.blocking) {
      // 阻断型缺失：必须先补数据
      integrityDialogVisible.value = true
      // 不继续核算
      return
    }
    if (res.data?.missingRanges?.length > 0) {
      // 非阻断型缺失（银行流水/额外费用）：警告后让用户选择是否继续
      pendingCalculateFn = async () => {
        await invokeCalculateByRange(startDate, endDate)
      }
      integrityDialogVisible.value = true
      return
    }
    // 无缺失，直接核算
    await invokeCalculateByRange(startDate, endDate)
  } catch (e) {
    // 错误已由请求拦截器统一处理
  } finally {
    checking.value = false
  }
}

// 实际调用 calculate-by-range 接口
async function invokeCalculateByRange(startDate, endDate) {
  try {
    await calculateProfitByRange(startDate, endDate)
    ElMessage.success('核算完成')
    // 通知其他子页面同步本次核算的日期范围
    accountingStore.notifyCalcSuccess('RANGE', '', [startDate, endDate])
    loadData()
  } catch (e) {
    // 错误已由请求拦截器统一处理
  }
}

// 完整性弹窗：用户点击"仍然继续核算"
async function confirmContinueCalculate() {
  integrityDialogVisible.value = false
  if (pendingCalculateFn) {
    const fn = pendingCalculateFn
    pendingCalculateFn = null
    await fn()
  }
}

// 完整性弹窗：用户点击"取消"
function cancelContinueCalculate() {
  integrityDialogVisible.value = false
  pendingCalculateFn = null
}

// 加载利润明细（筛选条件传后端，先筛选再分页；同时返回全量汇总）
async function loadData() {
  loading.value = true
  try {
    let params
    if (calcMode.value === 'MONTH') {
      params = { period: period.value, ...page }
    } else if (dateRange.value && dateRange.value.length === 2) {
      params = { startDate: dateRange.value[0], endDate: dateRange.value[1], ...page }
    } else {
      params = { ...page }
    }
    // 叠加筛选条件（后端先筛选再分页，保证每页数量等于分页大小）
    params.platform = tableFilter.platform || undefined
    params.shopId = tableFilter.shopId || undefined
    params.currency = tableFilter.currency || undefined
    params.reconcileStatus = (tableFilter.reconcileStatus === '' || tableFilter.reconcileStatus == null)
      ? undefined
      : Number(tableFilter.reconcileStatus)
    const res = await pageProfitDetail(params)
    // 后端返回 { page: { records, total }, summary: {...}, options: {...} }
    const pageData = res.data?.page || {}
    tableData.value = pageData.records || []
    total.value = pageData.total || 0
    tableSummary.value = res.data?.summary || null
    // 更新筛选下拉选项（当前周期下的全量选项，不受筛选影响）
    const opts = res.data?.options || {}
    platformOptions.value = (opts.platforms || []).map(v => ({ label: v, value: v }))
    shopOptions.value = (opts.shops || []).map(v => ({ label: v, value: v }))
    currencyOptions.value = (opts.currencies || []).map(v => ({ label: v, value: v }))
  } catch (e) {
    // 错误已由请求拦截器统一处理
  } finally {
    loading.value = false
  }
}

function handleSizeChange(size) {
  page.size = size
  page.page = 1
  loadData()
}

function handleCurrentChange(p) {
  page.page = p
  loadData()
}

// 周期变化时自动加载报表数据（仅 MONTH 模式）
watch(period, () => {
  if (calcMode.value === 'MONTH' && period.value) {
    page.page = 1
    loadData()
  }
})

// 日期范围变化时自动加载报表数据（仅 RANGE 模式）
watch(dateRange, () => {
  if (calcMode.value === 'RANGE' && dateRange.value && dateRange.value.length === 2) {
    page.page = 1
    loadData()
  }
})

// 完整性检查：类型 → el-tag 颜色映射
function typeTagColor(type) {
  const map = {
    BILL: 'danger',
    BANK_FLOW: 'warning',
    EXCHANGE_RATE: 'danger',
    EXTRA_COST: 'info'
  }
  return map[type] || 'info'
}

// 完整性检查：类型 → 处理建议文案
function typeSuggestion(type) {
  const map = {
    BILL: '请前往"数据底座 → 账单导入清洗"补齐对应日期的平台账单数据',
    BANK_FLOW: '建议前往"数据底座 → 银行流水导入"补齐对应日期的流水，以保证对账完整性',
    EXCHANGE_RATE: '请前往"数据底座 → 历史汇率导入"补齐对应日期的汇率快照',
    EXTRA_COST: '建议补齐对应日期的额外费用（物流/广告/仓储等），以保证成本归集完整'
  }
  return map[type] || '请补齐对应数据'
}

// 完整性检查：类型 → 跳转路由（用于「去补数据」按钮）
function typeRoute(type) {
  const map = {
    BILL: '/data/bill-import',
    BANK_FLOW: '/data/bank-reconciliation',
    EXCHANGE_RATE: '/data/exchange-rate-import',
    EXTRA_COST: '/data/cost-import'
  }
  return map[type] || null
}

// 跳转到对应页面补数据，并通过 query 传递缺失日期范围（目标页面可读取后预填）
function goToFillData(row) {
  const route = typeRoute(row.type)
  if (!route) {
    ElMessage.warning('暂未提供该数据类型的补录入口')
    return
  }
  // 关闭弹窗
  integrityDialogVisible.value = false
  // 通过 query 传递缺失日期范围，目标页面可在 onMounted 中读取并预填到日期选择器
  router.push({
    path: route,
    query: {
      missingStart: row.startDate,
      missingEnd: row.endDate
    }
  })
}

// 跳转到汇率导入页面补汇率（多币种汇率缺失时使用）
function goToFillCurrencyRate(row) {
  integrityDialogVisible.value = false
  router.push({
    path: '/data/exchange-rate-import',
    query: {
      missingStart: row.ranges?.[0]?.startDate || integrityResult.value?.startDate,
      missingEnd: row.ranges?.[row.ranges.length - 1]?.endDate || integrityResult.value?.endDate,
      currency: row.currency
    }
  })
}

// 自动补全缺失日期的汇率（用最近交易日汇率填充）
const autoFillingCurrency = ref(null)
async function autoFillCurrencyRate(row) {
  try {
    await ElMessageBox.confirm(
      `将使用 ${row.currency}/CNY 最近交易日的汇率自动填充缺失日期。汇率相对稳定时此操作安全，但若汇率波动较大可能产生误差。是否继续？`,
      '汇率自动补全确认',
      { type: 'warning', confirmButtonText: '继续', cancelButtonText: '取消' }
    )
  } catch (_) {
    return // 用户取消
  }
  autoFillingCurrency.value = row.currency
  try {
    // 遍历所有缺失区间，逐个调用后端自动补全
    let totalFilled = 0
    let totalMissing = 0
    for (const range of row.ranges) {
      const res = await autoFillExchangeRate(row.currency, row.targetCurrency, range.startDate, range.endDate)
      totalMissing += res.data?.missingCount || 0
      totalFilled += res.data?.filledCount || 0
    }
    if (totalFilled > 0) {
      ElMessage.success(`汇率自动补全完成：缺失 ${totalMissing} 天，成功填充 ${totalFilled} 天` +
        (totalMissing - totalFilled > 0 ? `，${totalMissing - totalFilled} 天因无邻近汇率跳过` : ''))
      // 重新触发完整性检查（刷新弹窗数据）
      if (calcMode.value === 'MONTH') {
        const { firstDay, lastDay } = periodToRange(period.value)
        const res = await checkDataIntegrity(firstDay, lastDay)
        integrityResult.value = res.data
        if (!res.data?.blocking && (res.data?.missingRanges?.length === 0) && (res.data?.currencyRateMissing?.length === 0)) {
          integrityDialogVisible.value = false
        }
      } else if (dateRange.value && dateRange.value.length === 2) {
        const res = await checkDataIntegrity(dateRange.value[0], dateRange.value[1])
        integrityResult.value = res.data
        if (!res.data?.blocking && (res.data?.missingRanges?.length === 0) && (res.data?.currencyRateMissing?.length === 0)) {
          integrityDialogVisible.value = false
        }
      }
    } else {
      ElMessage.warning(`未能补全任何汇率：${row.currency}/CNY 在缺失日期前后均无历史汇率记录，请先导入至少一天的历史汇率`)
    }
  } catch (e) {
    // 错误已由请求拦截器统一处理
  } finally {
    autoFillingCurrency.value = null
  }
}

/* ==================== 页面状态持久化 ==================== */
// 切换页面后再切回时，自动恢复上次的核算模式、周期、日期范围、页号、筛选
watch(calcMode, v => saveField('calcMode', v))
watch(period, v => saveField('period', v))
watch(dateRange, v => saveField('dateRange', v), { deep: true })
watch(page, v => saveField('page', { ...v }), { deep: true })
watch(tableFilter, v => saveField('tableFilter', { ...v }), { deep: true })

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
  // period 已默认为上个月（或同步/恢复的周期），初始化时加载报表
  loadData()
})
</script>

<template>
  <div>
    <!-- 顶部：核算模式选择与触发核算 -->
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
          <el-button
            type="primary"
            v-if="canCalculate"
            @click="handleCalculate"
            :loading="checking"
          >
            <el-icon><Promotion /></el-icon>
            {{ checking ? '正在检查数据完整性...' : '触发核算' }}
          </el-button>
          <span v-else style="color: #909399; font-size: 13px">仅财务/管理员可触发核算</span>
        </el-form-item>
        <el-form-item>
          <el-button
            type="success"
            @click="handleExport"
            :loading="exporting"
            :disabled="(calcMode === 'MONTH' && !period) || (calcMode === 'RANGE' && (!dateRange || dateRange.length !== 2))"
          >
            <el-icon><Download /></el-icon> 导出 Excel
          </el-button>
        </el-form-item>
        <el-form-item v-if="calcMode === 'RANGE'">
          <el-tooltip content="日期范围模式下，触发核算前会自动检查账单/汇率等数据完整性并给出精确到日的缺失提示" placement="top">
            <el-icon style="color: #909399; font-size: 16px"><InfoFilled /></el-icon>
          </el-tooltip>
        </el-form-item>
      </el-form>
    </div>

    <!-- 完整性检查结果弹窗 -->
    <el-dialog
      v-model="integrityDialogVisible"
      title="数据完整性检查结果"
      width="800px"
      :close-on-click-modal="false"
    >
      <div v-if="integrityResult">
        <el-alert
          :type="integrityResult.blocking ? 'error' : 'warning'"
          :closable="false"
          style="margin-bottom: 16px"
        >
          <template #title>
            <span v-if="integrityResult.blocking">
              检测到 <b style="color: #f56c6c">{{ integrityResult.summary.BILL?.missingDays || 0 }}</b> 天平台账单缺失
              <span v-if="integrityResult.summary.EXCHANGE_RATE?.missingDays > 0">
                、<b style="color: #f56c6c">{{ integrityResult.summary.EXCHANGE_RATE.missingDays }}</b> 天汇率缺失
              </span>
              <span v-if="integrityResult.currencyRateMissing?.length > 0">
                、<b style="color: #f56c6c">{{ integrityResult.currencyRateMissing.length }}</b> 个币种缺少对应 CNY 汇率
              </span>
              ，将影响核算准确性。请先补齐对应日期的数据后再核算。
            </span>
            <span v-else>
              范围内 {{ integrityResult.totalDays }} 天中，存在部分非阻断型缺失（银行流水/额外费用）。
              缺失数据不影响核算进行，但可能影响对账与分析准确性。
            </span>
          </template>
        </el-alert>

        <!-- 汇总统计 -->
        <el-descriptions :column="2" border size="small" style="margin-bottom: 16px">
          <el-descriptions-item label="查询范围">
            {{ integrityResult.startDate }} 至 {{ integrityResult.endDate }}
          </el-descriptions-item>
          <el-descriptions-item label="总天数">
            {{ integrityResult.totalDays }} 天
          </el-descriptions-item>
          <el-descriptions-item v-for="(s, key) in integrityResult.summary" :key="key" :label="s.typeName">
            <span>已覆盖 <b style="color: #67c23a">{{ s.coveredDays }}</b> 天</span>
            <span v-if="s.missingDays > 0" style="margin-left: 12px; color: #f56c6c">
              缺失 <b>{{ s.missingDays }}</b> 天（{{ s.firstMissing }} ~ {{ s.lastMissing }}）
            </span>
            <span v-else style="margin-left: 12px; color: #67c23a">无缺失</span>
          </el-descriptions-item>
        </el-descriptions>

        <!-- 缺失明细按类型分组展示 -->
        <div v-if="integrityResult.missingRanges?.length > 0">
          <div style="font-weight: 600; margin-bottom: 8px">缺失明细（按类型分组）：</div>
          <el-table :data="integrityResult.missingRanges" border stripe size="small" max-height="320">
            <el-table-column label="数据类型" width="120">
              <template #default="{ row }">
                <el-tag :type="typeTagColor(row.type)" size="small">{{ row.typeName }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="缺失日期范围" min-width="220">
              <template #default="{ row }">
                {{ row.startDate }} 至 {{ row.endDate }}
              </template>
            </el-table-column>
            <el-table-column prop="days" label="缺失天数" width="90" align="center" />
            <el-table-column label="处理建议" min-width="200">
              <template #default="{ row }">
                <span style="color: #606266">{{ typeSuggestion(row.type) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="120" align="center" fixed="right">
              <template #default="{ row }">
                <el-button type="primary" size="small" link @click="goToFillData(row)">
                  <el-icon><Right /></el-icon> 去补数据
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <!-- 多币种汇率缺失明细 -->
        <div v-if="integrityResult.currencyRateMissing?.length > 0" style="margin-top: 16px">
          <div style="font-weight: 600; margin-bottom: 8px; color: #f56c6c">
            多币种汇率缺失（订单涉及但缺少对应币种到 CNY 的汇率）：
          </div>
          <el-table :data="integrityResult.currencyRateMissing" border stripe size="small" max-height="240">
            <el-table-column label="币种对" width="160">
              <template #default="{ row }">
                <el-tag type="danger" size="small">{{ row.currency }} / {{ row.targetCurrency }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="缺失/涉及" width="110" align="center">
              <template #default="{ row }">
                <span style="color: #f56c6c">{{ row.missingDays }}</span> / {{ row.requiredDays }} 天
              </template>
            </el-table-column>
            <el-table-column label="缺失日期区间" min-width="260">
              <template #default="{ row }">
                <div v-for="r in row.ranges" :key="r.startDate" style="line-height: 1.6">
                  {{ r.startDate }} 至 {{ r.endDate }}（{{ r.days }} 天）
                </div>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="220" align="center" fixed="right">
              <template #default="{ row }">
                <el-button type="success" size="small" link @click="autoFillCurrencyRate(row)" :loading="autoFillingCurrency === row.currency">
                  <el-icon><MagicStick /></el-icon> 一键自动补全
                </el-button>
                <el-button type="primary" size="small" link @click="goToFillCurrencyRate(row)">
                  <el-icon><Right /></el-icon> 手动补录
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>
      <template #footer>
        <el-button @click="cancelContinueCalculate">取消</el-button>
        <el-button
          v-if="!integrityResult?.blocking"
          type="primary"
          @click="confirmContinueCalculate"
        >
          仍然继续核算
        </el-button>
        <span v-else style="color: #909399; font-size: 13px">请先补齐阻断型缺失数据</span>
      </template>
    </el-dialog>

    <!-- 利润明细表格 -->
    <div class="page-card">
      <div class="section-title">
        <span>利润明细</span>
        <el-button size="small" link style="margin-left: 12px" @click="resetTableFilter">
          <el-icon><RefreshLeft /></el-icon> 重置筛选
        </el-button>
      </div>
      <!-- 表格筛选行（筛选条件传后端，先筛选再分页） -->
      <div class="table-filter-row">
        <el-select v-model="tableFilter.platform" placeholder="平台" clearable size="small" style="width: 140px" @change="handleFilterChange">
          <el-option v-for="o in platformOptions" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-select v-model="tableFilter.shopId" placeholder="店铺" clearable filterable size="small" style="width: 160px" @change="handleFilterChange">
          <el-option v-for="o in shopOptions" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-select v-model="tableFilter.currency" placeholder="币种" clearable size="small" style="width: 110px" @change="handleFilterChange">
          <el-option v-for="o in currencyOptions" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-select v-model="tableFilter.reconcileStatus" placeholder="对账状态" clearable size="small" style="width: 130px" @change="handleFilterChange">
          <el-option :value="0" label="未对账" />
          <el-option :value="1" label="已完成" />
          <el-option :value="2" label="对账失败" />
          <el-option :value="3" label="未到账" />
          <el-option :value="4" label="不明入账" />
        </el-select>
        <span class="filter-summary" v-if="tableSummary">
          筛选后 {{ tableSummary.orderCount }} 笔 · 账单 ¥{{ formatMoney(tableSummary.totalBillAmount) }}
          · 利润 ¥{{ formatMoney(tableSummary.totalProfitAmount) }}
        </span>
      </div>
      <el-table :data="filteredTableData" v-loading="loading" border stripe style="width: 100%"
                show-summary :summary-method="getSummary"
                :default-sort="{ prop: 'profitAmount', order: 'descending' }"
                @sort-change="handleSortChange">
        <el-table-column label="序号" width="70" fixed="left">
          <template #default="{ $index }">{{ (page.page - 1) * page.size + $index + 1 }}</template>
        </el-table-column>
        <el-table-column prop="orderNo" label="订单号" min-width="180" show-overflow-tooltip fixed="left" />
        <el-table-column prop="shopId" label="店铺" width="110" show-overflow-tooltip />
        <el-table-column prop="orderTime" label="订单时间" width="160">
          <template #default="{ row }">{{ formatTime(row.orderTime) }}</template>
        </el-table-column>
        <!-- ===== 账单/银行金额列（与对账模式口径一致，集中展示方便比对；均为 CNY） ===== -->
        <el-table-column prop="platformAmountCny" label="账单金额" width="140" align="right" sortable="custom">
          <template #default="{ row }">¥ {{ formatMoney(row.platformAmountCny) }}<span class="unit">CNY</span></template>
        </el-table-column>
        <el-table-column prop="platformFeeCny" label="平台手续费" width="130" align="right" sortable="custom">
          <template #default="{ row }">
            <span style="color: #409eff">¥ {{ formatMoney(row.platformFeeCny) }}</span><span class="unit">CNY</span>
          </template>
        </el-table-column>
        <el-table-column prop="transferFeeCny" label="中转费" width="120" align="right" sortable="custom">
          <template #default="{ row }">¥ {{ formatMoney(row.transferFeeCny) }}<span class="unit">CNY</span></template>
        </el-table-column>
        <el-table-column prop="bankReceivedCny" label="银行到账" width="140" align="right" sortable="custom">
          <template #default="{ row }">
            <span v-if="row.bankReceivedCny === null || row.bankReceivedCny === undefined" style="color: #c0c4cc">-</span>
            <span v-else :style="{ color: Number(row.bankReceivedCny) > 0 ? '#67c23a' : '#909399' }">
              ¥ {{ formatMoney(row.bankReceivedCny) }}<span class="unit">CNY</span>
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="reconcileDiff" label="对账差值" width="140" align="right" sortable="custom">
          <template #default="{ row }">
            <span v-if="row.reconcileDiff !== null && row.reconcileDiff !== undefined"
                  :style="{ color: row.reconcileStatus === 1 ? '#c0c4cc' : (Number(row.reconcileDiff) > 0 ? '#f56c6c' : '#e6a23c') }"
                  :class="{ 'diff-tiny': row.reconcileStatus === 1 }">
              ¥ {{ formatMoney(row.reconcileDiff) }}<span class="unit">CNY</span>
            </span>
            <span v-else style="color: #c0c4cc">-</span>
          </template>
        </el-table-column>
        <!-- ===== 利润核算列（均为 CNY） ===== -->
        <el-table-column prop="sharedCost" label="公共分摊" width="120" align="right" sortable="custom">
          <template #default="{ row }">
            <span style="color: #e6a23c">¥ {{ formatMoney(row.sharedCost) }}</span><span class="unit">CNY</span>
          </template>
        </el-table-column>
        <el-table-column prop="directCost" label="直接成本" width="120" align="right" sortable="custom">
          <template #default="{ row }">
            <span style="color: #67c23a">¥ {{ formatMoney(row.directCost) }}</span><span class="unit">CNY</span>
          </template>
        </el-table-column>
        <el-table-column prop="costAmount" label="总成本" width="140" align="right" sortable="custom">
          <template #default="{ row }">¥ {{ formatMoney(row.costAmount) }}<span class="unit">CNY</span></template>
        </el-table-column>
        <el-table-column prop="profitAmount" label="利润" width="140" align="right" sortable="custom">
          <template #default="{ row }">
            <span :style="{ color: row.profitAmount >= 0 ? '#67c23a' : '#f56c6c', fontWeight: 600 }">
              ¥ {{ formatMoney(row.profitAmount) }}
            </span><span class="unit">CNY</span>
          </template>
        </el-table-column>
        <el-table-column prop="profitRate" label="利润率" width="100" align="right" sortable="custom">
          <template #default="{ row }">{{ formatRate(row.profitRate) }}</template>
        </el-table-column>
        <el-table-column label="对账状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="reconcileStatusTag(row.reconcileStatus).type" size="small">
              {{ reconcileStatusTag(row.reconcileStatus).text }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>

      <div class="flex-between" style="margin-top: 16px">
        <span style="color: #909399">共 {{ total }} 条</span>
        <el-pagination
          v-model:current-page="page.page"
          v-model:page-size="page.size"
          :page-sizes="[10, 20, 50, 100]"
          :total="total"
          layout="sizes, prev, pager, next, jumper"
          @size-change="handleSizeChange"
          @current-change="handleCurrentChange"
        />
      </div>
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
/* 对账已完成时的微小差值（灰色小字，表示容差内非真实业务差异） */
.diff-tiny {
  font-size: 12px;
}
/* 金额单位（浅灰小字，跟在金额后面） */
.unit {
  color: #909399;
  font-size: 12px;
  margin-left: 4px;
}
/* 表格筛选行 */
.table-filter-row {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
  align-items: center;
  flex-wrap: wrap;
}
.filter-summary {
  margin-left: auto;
  font-size: 13px;
  color: #606266;
  background: #f5f7fa;
  padding: 4px 12px;
  border-radius: 4px;
}
@media (max-width: 768px) {
  .filter-summary { margin-left: 0; width: 100%; }
}
</style>
