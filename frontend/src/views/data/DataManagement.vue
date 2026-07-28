<script setup>
// 数据管理页：账单/银行流水、额外费用、汇率的在线查询/编辑/删除
// 多条件组合筛选 + 双击单元格编辑 + 单条/批量删除
// 权限：ADMIN 可编辑/删除，FINANCE/OPERATOR 只读
import { reactive, ref, computed, onMounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/store'
import { usePageState } from '@/composables/usePageState'
import request from '@/utils/request'
import {
  pageOrder, updateOrder, deleteOrder, batchDeleteOrders, addOrder,
  batchUpdateOrders, exportOrders, getOrderPlatforms,
  pageCost, updateCost, deleteCost, batchDeleteCosts, addCost, fillZeroCost,
  batchUpdateCosts, exportCosts,
  pageRate, updateRate, deleteRate, batchDeleteRates, addRate,
  batchUpdateRates, exportRates, exportProfitReport
} from '@/api/dataManage'
import { checkDataIntegrity } from '@/api/accounting'
import PeriodFilter from '@/components/PeriodFilter.vue'

const router = useRouter()

const route = useRoute()
const userStore = useUserStore()
const canEdit = computed(() => {
  const roles = userStore.roles || []
  return roles.includes('ADMIN')
})

// 页面状态保持（筛选条件、页号、操作模式等切换页面后自动恢复）
const { loadField, saveField } = usePageState('DataManagement')

// 当前激活的 Tab
const activeTab = ref(loadField('activeTab', 'order'))

// 费用类型映射
const COST_TYPE_MAP = {
  LOGISTICS: '物流费', WAREHOUSE: '仓储费', ADVERTISING: '广告费',
  CUSTOMS_DUTY: '关税税费', COMMISSION: '平台佣金', FX_LOSS: '汇兑损失',
  RETURN_LOSS: '退货损失', TRANSACTION_FEE: '手续费', TRANSFER_FEE: '中转手续费',
  PACKAGING: '包装费', OTHER: '其他'
}
const costTypeOptions = Object.entries(COST_TYPE_MAP).map(([value, label]) => ({ value, label }))

// 币种选项（枚举值，用下拉栏避免用户输错大小写）
const currencyOptions = [
  { label: '人民币 CNY', value: 'CNY' },
  { label: '美元 USD', value: 'USD' },
  { label: '欧元 EUR', value: 'EUR' },
  { label: '港币 HKD', value: 'HKD' },
  { label: '日元 JPY', value: 'JPY' }
]

// 平台选项（动态加载：账单模式显示电商平台，银行模式显示银行名称，对账模式合并显示）
const platformOptions = ref([])
async function loadPlatformOptions(source) {
  try {
    const res = await getOrderPlatforms(source)
    const list = res.data || []
    platformOptions.value = list.map(v => ({ label: v, value: v }))
  } catch (e) {
    platformOptions.value = []
  }
}

// 对话框专用平台选项（编辑/新增时按表单 source 独立加载，避免与筛选栏互相干扰）
const dialogPlatformOptions = ref([])
async function loadDialogPlatformOptions(source) {
  try {
    const res = await getOrderPlatforms(source)
    const list = res.data || []
    dialogPlatformOptions.value = list.map(v => ({ label: v, value: v }))
  } catch (e) {
    dialogPlatformOptions.value = []
  }
}

// 数据来源选项（账单/银行流水区分，汇率来源标记）
const sourceOptions = [
  { label: '平台账单', value: 'PLATFORM' },
  { label: '银行流水', value: 'BANK' },
  { label: '手工录入', value: 'MANUAL' },
  { label: '央行', value: '央行' },
  { label: '第三方', value: '第三方' },
  { label: '自动补全', value: 'AUTO_FILL' }
]

// 费用状态选项
const costStatusOptions = [
  { label: '生效', value: 1 },
  { label: '已作废', value: 0 }
]

// 对账状态选项（只读展示用，编辑不可改）
const reconcileStatusOptions = [
  { label: '未对账', value: 0 },
  { label: '已完成', value: 1 },
  { label: '对账失败', value: 2 },
  { label: '未到账', value: 3 },
  { label: '不明入账', value: 4 }
]

// 对账状态码 → 标签 + 颜色（用扩展的 reconcile_status 值替代原 reconcile_type 语义）
const RECONCILE_STATUS_MAP = {
  0: { label: '未对账', tagType: 'info' },
  1: { label: '已完成', tagType: 'success' },
  2: { label: '对账失败', tagType: 'danger' },
  3: { label: '未到账', tagType: 'warning' },
  4: { label: '不明入账', tagType: 'warning' }
}

function formatReconcileStatus(status) {
  return RECONCILE_STATUS_MAP[status]?.label || '未对账'
}
function reconcileStatusTagType(status) {
  return RECONCILE_STATUS_MAP[status]?.tagType || 'info'
}

function formatCostType(type) {
  return COST_TYPE_MAP[type] || type || '-'
}

function formatTime(t) {
  if (!t) return '-'
  return String(t).replace('T', ' ').split('.')[0]
}

function formatDate(t) {
  if (!t) return '-'
  return String(t).replace('T', ' ').split(' ')[0]
}

function formatMoney(num) {
  if (num === null || num === undefined || num === '') return '-'
  const n = Number(num)
  if (isNaN(n)) return '-'
  return n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

// 日期时间范围选择器的默认时间：开始 00:00:00，结束 23:59:59
const defaultTime = [
  new Date(2000, 0, 1, 0, 0, 0),
  new Date(2000, 0, 1, 23, 59, 59)
]

// 序号列：基于分页的连续行号（跨页递增）
function orderIndexMethod(index) {
  return (orderPage.page - 1) * orderPage.size + index + 1
}
function costIndexMethod(index) {
  return (costPage.page - 1) * costPage.size + index + 1
}
function rateIndexMethod(index) {
  return (ratePage.page - 1) * ratePage.size + index + 1
}

/* ==================== 账单/银行流水 ==================== */
// 操作模式：standard 标准CRUD / reconcile 对账模式
const orderMode = ref(loadField('orderMode', 'standard'))

const orderFilter = reactive(loadField('orderFilter', {
  source: '', orderNo: '', platform: '', currency: '', batchNo: ''
}))
// 账单/银行流水核算周期筛选（通用组件，替代原 dateRange）
const orderPeriod = ref(loadField('orderPeriod', { mode: 'MONTH', period: '', startDate: '', endDate: '' }))
const orderData = ref([])
const orderTotal = ref(0)
const orderLoading = ref(false)
const orderPage = reactive(loadField('orderPage', { page: 1, size: 10 }))
const orderSelection = ref([])
// 账单/银行流水表格引用：用于跨页保留选中后手动清空选择
const orderTableRef = ref(null)

// 对账模式专用状态
const reconcilePeriod = ref(loadField('reconcilePeriod', { mode: 'MONTH', period: '', startDate: '', endDate: '' }))
const reconcileStatusFilter = ref(loadField('reconcileStatusFilter', ''))
const reconcileFilter = reactive(loadField('reconcileFilter', { platform: '', currency: '', orderNo: '' }))
const reconcileSummary = ref({ total: 0, matched: 0, amountDiff: 0, platformOnly: 0, bankOnly: 0, pending: 0 })
const autoReconciling = ref(false)

async function loadOrders() {
  orderLoading.value = true
  try {
    // 对账模式：调用对账结果查询接口（同时返回平台+银行数据）
    if (orderMode.value === 'reconcile') {
      const params = {
        ...orderPage,
        startDate: reconcilePeriod.value?.startDate || undefined,
        endDate: reconcilePeriod.value?.endDate || undefined,
        reconcileStatus: (reconcileStatusFilter.value === '' || reconcileStatusFilter.value == null)
          ? undefined
          : Number(reconcileStatusFilter.value),
        platform: reconcileFilter.platform || undefined,
        currency: reconcileFilter.currency || undefined,
        orderNo: reconcileFilter.orderNo || undefined
      }
      const res = await request({
        url: '/data/bank-flow/reconcile/result',
        method: 'get',
        params
      })
      orderData.value = res.data?.records || []
      orderTotal.value = res.data?.total || 0
      loadReconcileSummary()
      return
    }
    // 标准模式：调用原分页查询接口
    const params = {
      ...orderPage,
      source: orderFilter.source || undefined,
      orderNo: orderFilter.orderNo || undefined,
      platform: orderFilter.platform || undefined,
      currency: orderFilter.currency || undefined,
      batchNo: orderFilter.batchNo || undefined,
      startDate: orderPeriod.value?.startDate || undefined,
      endDate: orderPeriod.value?.endDate || undefined
    }
    const res = await pageOrder(params)
    orderData.value = res.data?.records || []
    orderTotal.value = res.data?.total || 0
  } catch (e) {}
  finally { orderLoading.value = false }
}

function handleOrderSearch() {
  orderPage.page = 1
  loadOrders()
}

function handleOrderReset() {
  Object.assign(orderFilter, { source: '', orderNo: '', platform: '', currency: '', batchNo: '' })
  orderPeriod.value = { mode: 'MONTH', period: '', startDate: '', endDate: '' }
  orderPage.page = 1
  loadPlatformOptions(undefined)
  loadOrders()
}

function handleOrderSelectionChange(val) {
  orderSelection.value = val
}

// 编辑账单
const orderEditVisible = ref(false)
const orderEditForm = ref({})
function handleEditOrder(row) {
  if (!canEdit.value) return
  orderEditForm.value = { ...row }
  // 按当前记录的 source 加载对应平台列表（账单=电商平台，银行流水=银行名称）
  loadDialogPlatformOptions(row.source || undefined)
  orderEditVisible.value = true
}

async function submitOrderEdit() {
  try {
    await updateOrder(orderEditForm.value.id, orderEditForm.value)
    ElMessage.success('修改成功')
    orderEditVisible.value = false
    loadOrders()
  } catch (e) {}
}

async function handleDeleteOrder(row) {
  try {
    await ElMessageBox.confirm(`确认删除订单 ${row.orderNo || row.id}？此操作不可恢复`, '删除确认', { type: 'warning' })
  } catch (_) { return }
  try {
    await deleteOrder(row.id)
    ElMessage.success('删除成功')
    loadOrders()
  } catch (e) {}
}

async function handleBatchDeleteOrders() {
  if (orderSelection.value.length === 0) {
    ElMessage.warning('请先选择要删除的记录')
    return
  }
  try {
    await ElMessageBox.confirm(`确认批量删除选中的 ${orderSelection.value.length} 条记录？此操作不可恢复`, '批量删除确认', { type: 'warning' })
  } catch (_) { return }
  try {
    const ids = orderSelection.value.map(r => r.id)
    await batchDeleteOrders(ids)
    ElMessage.success(`已删除 ${ids.length} 条记录`)
    // 清空跨页保留的选中状态（已删除的行不再保留选中）
    orderTableRef.value?.clearSelection()
    loadOrders()
  } catch (e) {}
}

// 切换操作模式
function handleOrderModeChange(mode) {
  orderMode.value = mode
  orderPage.page = 1
  // 对账模式合并显示所有平台/银行；标准模式按 source 显示对应列表
  loadPlatformOptions(mode === 'reconcile' ? undefined : (orderFilter.source || undefined))
  loadOrders()
}

// 加载对账汇总
async function loadReconcileSummary() {
  try {
    const res = await request({
      url: '/data/bank-flow/reconcile/summary',
      method: 'get',
      params: {
        startDate: reconcilePeriod.value?.startDate || undefined,
        endDate: reconcilePeriod.value?.endDate || undefined
      }
    })
    reconcileSummary.value = res.data || { total: 0, matched: 0, amountDiff: 0, platformOnly: 0, bankOnly: 0, pending: 0 }
  } catch (e) {}
}

// 触发自动对账
async function handleAutoReconcile() {
  const start = reconcilePeriod.value?.startDate
  const end = reconcilePeriod.value?.endDate
  if (!start || !end) {
    ElMessage.warning('请先选择对账周期')
    return
  }
  try {
    await ElMessageBox.confirm(
      `将对 ${start} 至 ${end} 范围内的平台账单与银行流水执行自动对账，该范围内原有对账状态将被重置。是否继续？`,
      '自动对账确认',
      { type: 'warning' }
    )
  } catch (_) { return }
  autoReconciling.value = true
  try {
    const res = await request({
      url: '/data/bank-flow/reconcile/auto',
      method: 'post',
      params: {
        startDate: start,
        endDate: end
      }
    })
    const s = res.data || {}
    ElMessage.success(
      `对账完成：共 ${s.total} 条，已匹配 ${s.matched}，金额差异 ${s.amountDiff}，未到账 ${s.platformOnly}，不明入账 ${s.bankOnly}`
    )
    orderPage.page = 1
    loadOrders()
  } catch (e) {}
  finally { autoReconciling.value = false }
}

// 批量标记差异已处理
async function handleBatchResolve() {
  if (orderSelection.value.length === 0) {
    ElMessage.warning('请先选择要标记的记录')
    return
  }
  try {
    const ids = orderSelection.value.map(r => r.id)
    await request({
      url: '/data/bank-flow/reconcile/resolve',
      method: 'post',
      data: ids
    })
    ElMessage.success(`已标记 ${ids.length} 条记录为已处理`)
    // 清空跨页保留的选中状态，避免对同一批数据重复操作
    orderTableRef.value?.clearSelection()
    loadOrders()
  } catch (e) {}
}

// 批量取消对账
async function handleBatchCancelReconcile() {
  if (orderSelection.value.length === 0) {
    ElMessage.warning('请先选择要取消对账的记录')
    return
  }
  try {
    const ids = orderSelection.value.map(r => r.id)
    await request({
      url: '/data/bank-flow/reconcile/cancel',
      method: 'post',
      data: ids
    })
    ElMessage.success(`已取消对账 ${ids.length} 条记录`)
    // 清空跨页保留的选中状态，避免对同一批数据重复操作
    orderTableRef.value?.clearSelection()
    loadOrders()
  } catch (e) {}
}

// 单条标记差异已处理
async function handleSingleResolve(row) {
  try {
    await request({
      url: '/data/bank-flow/reconcile/resolve',
      method: 'post',
      data: [row.id]
    })
    ElMessage.success('已标记为已处理')
    loadOrders()
  } catch (e) {}
}

// 单条取消对账
async function handleSingleCancelReconcile(row) {
  try {
    await request({
      url: '/data/bank-flow/reconcile/cancel',
      method: 'post',
      data: [row.id]
    })
    ElMessage.success('取消对账完成')
    loadOrders()
  } catch (e) {}
}

/* ==================== 额外费用 ==================== */
const costFilter = reactive(loadField('costFilter', {
  costType: '', orderNo: '', currency: '', batchNo: ''
}))
// 额外费用核算周期筛选（通用组件，替代原 period + dateRange）
const costPeriod = ref(loadField('costPeriod', { mode: 'MONTH', period: '', startDate: '', endDate: '' }))
const costData = ref([])
const costTotal = ref(0)
const costLoading = ref(false)
const costPage = reactive(loadField('costPage', { page: 1, size: 10 }))
const costSelection = ref([])
// 额外费用表格引用：用于跨页保留选中后手动清空选择
const costTableRef = ref(null)

async function loadCosts() {
  costLoading.value = true
  try {
    const params = {
      ...costPage,
      costType: costFilter.costType || undefined,
      orderNo: costFilter.orderNo || undefined,
      period: costPeriod.value?.period || undefined,
      currency: costFilter.currency || undefined,
      batchNo: costFilter.batchNo || undefined,
      startDate: costPeriod.value?.startDate || undefined,
      endDate: costPeriod.value?.endDate || undefined
    }
    const res = await pageCost(params)
    costData.value = res.data?.records || []
    costTotal.value = res.data?.total || 0
  } catch (e) {}
  finally { costLoading.value = false }
}

function handleCostSearch() { costPage.page = 1; loadCosts() }
function handleCostReset() {
  Object.assign(costFilter, { costType: '', orderNo: '', currency: '', batchNo: '' })
  costPeriod.value = { mode: 'MONTH', period: '', startDate: '', endDate: '' }
  costPage.page = 1
  loadCosts()
}
function handleCostSelectionChange(val) { costSelection.value = val }

const costEditVisible = ref(false)
const costEditForm = ref({})
function handleEditCost(row) {
  if (!canEdit.value) return
  costEditForm.value = { ...row }
  costEditVisible.value = true
}
async function submitCostEdit() {
  try {
    await updateCost(costEditForm.value.id, costEditForm.value)
    ElMessage.success('修改成功')
    costEditVisible.value = false
    loadCosts()
  } catch (e) {}
}
async function handleDeleteCost(row) {
  try {
    await ElMessageBox.confirm(`确认删除该额外费用记录？此操作不可恢复`, '删除确认', { type: 'warning' })
  } catch (_) { return }
  try {
    await deleteCost(row.id)
    ElMessage.success('删除成功')
    loadCosts()
  } catch (e) {}
}
async function handleBatchDeleteCosts() {
  if (costSelection.value.length === 0) { ElMessage.warning('请先选择要删除的记录'); return }
  try {
    await ElMessageBox.confirm(`确认批量删除选中的 ${costSelection.value.length} 条记录？此操作不可恢复`, '批量删除确认', { type: 'warning' })
  } catch (_) { return }
  try {
    const ids = costSelection.value.map(r => r.id)
    await batchDeleteCosts(ids)
    ElMessage.success(`已删除 ${ids.length} 条记录`)
    // 清空跨页保留的选中状态（已删除的行不再保留选中）
    costTableRef.value?.clearSelection()
    loadCosts()
  } catch (e) {}
}

/* ==================== 汇率 ==================== */
const rateFilter = reactive(loadField('rateFilter', {
  fromCurrency: '', toCurrency: ''
}))
// 汇率核算周期筛选（通用组件，替代原 dateRange）
const ratePeriod = ref(loadField('ratePeriod', { mode: 'MONTH', period: '', startDate: '', endDate: '' }))
const rateData = ref([])
const rateTotal = ref(0)
const rateLoading = ref(false)
const ratePage = reactive(loadField('ratePage', { page: 1, size: 10 }))
const rateSelection = ref([])
// 汇率表格引用：用于跨页保留选中后手动清空选择
const rateTableRef = ref(null)

async function loadRates() {
  rateLoading.value = true
  try {
    const params = {
      ...ratePage,
      fromCurrency: rateFilter.fromCurrency || undefined,
      toCurrency: rateFilter.toCurrency || undefined,
      startDate: ratePeriod.value?.startDate || undefined,
      endDate: ratePeriod.value?.endDate || undefined
    }
    const res = await pageRate(params)
    rateData.value = res.data?.records || []
    rateTotal.value = res.data?.total || 0
  } catch (e) {}
  finally { rateLoading.value = false }
}

function handleRateSearch() { ratePage.page = 1; loadRates() }
function handleRateReset() {
  Object.assign(rateFilter, { fromCurrency: '', toCurrency: '' })
  ratePeriod.value = { mode: 'MONTH', period: '', startDate: '', endDate: '' }
  ratePage.page = 1
  loadRates()
}
function handleRateSelectionChange(val) { rateSelection.value = val }

const rateEditVisible = ref(false)
const rateEditForm = ref({})
function handleEditRate(row) {
  if (!canEdit.value) return
  rateEditForm.value = { ...row }
  rateEditVisible.value = true
}
async function submitRateEdit() {
  try {
    await updateRate(rateEditForm.value.id, rateEditForm.value)
    ElMessage.success('修改成功')
    rateEditVisible.value = false
    loadRates()
  } catch (e) {}
}
async function handleDeleteRate(row) {
  try {
    await ElMessageBox.confirm(`确认删除该汇率记录？此操作不可恢复`, '删除确认', { type: 'warning' })
  } catch (_) { return }
  try {
    await deleteRate(row.id)
    ElMessage.success('删除成功')
    loadRates()
  } catch (e) {}
}
async function handleBatchDeleteRates() {
  if (rateSelection.value.length === 0) { ElMessage.warning('请先选择要删除的记录'); return }
  try {
    await ElMessageBox.confirm(`确认批量删除选中的 ${rateSelection.value.length} 条记录？此操作不可恢复`, '批量删除确认', { type: 'warning' })
  } catch (_) { return }
  try {
    const ids = rateSelection.value.map(r => r.id)
    await batchDeleteRates(ids)
    ElMessage.success(`已删除 ${ids.length} 条记录`)
    // 清空跨页保留的选中状态（已删除的行不再保留选中）
    rateTableRef.value?.clearSelection()
    loadRates()
  } catch (e) {}
}

/* ==================== Tab 切换与初始化 ==================== */
function handleTabChange(tab) {
  if (tab === 'order' && orderData.value.length === 0) loadOrders()
  if (tab === 'cost' && costData.value.length === 0) loadCosts()
  if (tab === 'rate' && rateData.value.length === 0) loadRates()
}

// 分页变化
function handleOrderSizeChange(s) { orderPage.size = s; orderPage.page = 1; loadOrders() }
function handleOrderCurrentChange(p) { orderPage.page = p; loadOrders() }
function handleCostSizeChange(s) { costPage.size = s; costPage.page = 1; loadCosts() }
function handleCostCurrentChange(p) { costPage.page = p; loadCosts() }
function handleRateSizeChange(s) { ratePage.size = s; ratePage.page = 1; loadRates() }
function handleRateCurrentChange(p) { ratePage.page = p; loadRates() }

/* ==================== watch 筛选条件自动查询 ==================== */
// 账单/银行流水：筛选条件变化时立即查询（重置到第一页）
watch(
  () => ({ ...orderFilter }),
  () => {
    orderPage.page = 1
    loadOrders()
  },
  { deep: true }
)

// 额外费用：筛选条件变化时立即查询
watch(
  () => ({ ...costFilter }),
  () => {
    costPage.page = 1
    loadCosts()
  },
  { deep: true }
)

// 汇率：筛选条件变化时立即查询
watch(
  () => ({ ...rateFilter }),
  () => {
    ratePage.page = 1
    loadRates()
  },
  { deep: true }
)

/* ==================== 页面状态持久化 ==================== */
// 切换页面后再切回时，自动恢复上次的筛选条件、页号、操作模式
watch(activeTab, v => saveField('activeTab', v))
watch(orderMode, v => saveField('orderMode', v))
watch(orderFilter, v => saveField('orderFilter', { ...v }), { deep: true })
watch(orderPeriod, v => saveField('orderPeriod', { ...v }), { deep: true })
watch(orderPage, v => saveField('orderPage', { ...v }), { deep: true })
watch(reconcilePeriod, v => saveField('reconcilePeriod', { ...v }), { deep: true })
watch(reconcileStatusFilter, v => saveField('reconcileStatusFilter', v))
watch(reconcileFilter, v => saveField('reconcileFilter', { ...v }), { deep: true })
watch(costFilter, v => saveField('costFilter', { ...v }), { deep: true })
watch(costPeriod, v => saveField('costPeriod', { ...v }), { deep: true })
watch(costPage, v => saveField('costPage', { ...v }), { deep: true })
watch(rateFilter, v => saveField('rateFilter', { ...v }), { deep: true })
watch(ratePeriod, v => saveField('ratePeriod', { ...v }), { deep: true })
watch(ratePage, v => saveField('ratePage', { ...v }), { deep: true })

/* ==================== 数据完整性检查 ==================== */
// 用于利润核算前排查缺失数据：选择日期范围后一键检查 4 类数据覆盖情况
// 输入条件（mode/month/dateRange）、检查结果（result）、激活的 tab 均通过 Pinia 持久化，
// 切换页面再切回时可恢复上次的检查状态，无需重新请求接口
const integrityCheckDialogVisible = ref(false)
const integrityCheckLoading = ref(false)
// 检查模式：month 按月份 / range 自定义日期范围
const integrityMode = ref(loadField('integrityMode', 'month'))
// 月份选择值（YYYYMM）
const integrityMonth = ref(loadField('integrityMonth', ''))
// 自定义日期范围 [startDate, endDate]（YYYY-MM-DD）
const integrityDateRange = ref(loadField('integrityDateRange', []))
// 检查结果
const integrityResult = ref(loadField('integrityResult', null))
// 结果对话框中当前激活的数据类型 tab（ALL 全部汇总 / BILL / BANK_FLOW / EXCHANGE_RATE / EXTRA_COST）
const integrityActiveType = ref(loadField('integrityActiveType', 'ALL'))
// 数据完整性检查状态持久化（dialogVisible / loading 为临时状态，不持久化）
watch(integrityMode, v => saveField('integrityMode', v))
watch(integrityMonth, v => saveField('integrityMonth', v))
watch(integrityDateRange, v => saveField('integrityDateRange', v), { deep: true })
watch(integrityActiveType, v => saveField('integrityActiveType', v))
watch(integrityResult, v => saveField('integrityResult', v), { deep: true })

// 打开完整性检查对话框：保留上次的输入条件和结果（已持久化），便于用户继续查看或重新检查
function openIntegrityCheck() {
  integrityCheckDialogVisible.value = true
}

// 各数据类型 → tab 映射，用于「查看该日期数据」跳转
const TYPE_TO_TAB = {
  BILL: { tab: 'order', source: 'PLATFORM' },
  BANK_FLOW: { tab: 'order', source: 'BANK' },
  EXCHANGE_RATE: { tab: 'rate' },
  EXTRA_COST: { tab: 'cost' }
}

// 类型中文名映射（后端已返回 typeName，这里用于 tab 标签展示）
const TYPE_LABELS = {
  BILL: '平台账单',
  BANK_FLOW: '银行流水',
  EXCHANGE_RATE: '汇率',
  EXTRA_COST: '额外费用'
}

// 执行完整性检查
async function handleIntegrityCheck() {
  let startDate, endDate
  if (integrityMode.value === 'month') {
    if (!integrityMonth.value) {
      ElMessage.warning('请选择月份')
      return
    }
    // YYYYMM → 该月 1 号 ~ 末号
    const y = parseInt(integrityMonth.value.substring(0, 4))
    const m = parseInt(integrityMonth.value.substring(4, 6))
    const first = new Date(y, m - 1, 1)
    const last = new Date(y, m, 0)
    startDate = formatDateStr(first)
    endDate = formatDateStr(last)
  } else {
    if (!integrityDateRange.value || integrityDateRange.value.length !== 2) {
      ElMessage.warning('请选择日期范围')
      return
    }
    startDate = integrityDateRange.value[0]
    endDate = integrityDateRange.value[1]
  }

  integrityCheckLoading.value = true
  try {
    const res = await checkDataIntegrity(startDate, endDate)
    integrityResult.value = res.data
    integrityCheckDialogVisible.value = true
  } catch (e) {
    // 错误已由请求拦截器统一处理
  } finally {
    integrityCheckLoading.value = false
  }
}

// Date → 'YYYY-MM-DD'
function formatDateStr(d) {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

// 从检查结果跳转到对应 tab 查看缺失日期的数据
function jumpToMissingData(type, range) {
  const target = TYPE_TO_TAB[type]
  if (!target) return
  // 关闭对话框
  integrityCheckDialogVisible.value = false
  // 切换到对应 tab
  activeTab.value = target.tab
  // 预填日期范围（统一用 PeriodFilter 的 RANGE 模式）
  const start = range.startDate
  const end = range.endDate
  if (type === 'BILL' || type === 'BANK_FLOW') {
    orderMode.value = 'standard'
    orderFilter.source = target.source
    orderPeriod.value = { mode: 'RANGE', period: '', startDate: start, endDate: end }
    orderPage.page = 1
    loadOrders()
  } else if (type === 'EXTRA_COST') {
    costPeriod.value = { mode: 'RANGE', period: '', startDate: start, endDate: end }
    costPage.page = 1
    loadCosts()
  } else if (type === 'EXCHANGE_RATE') {
    ratePeriod.value = { mode: 'RANGE', period: '', startDate: start, endDate: end }
    ratePage.page = 1
    loadRates()
  }
  ElMessage.info(`已切换到「${TYPE_LABELS[type]}」并预填 ${start} ~ ${end} 的日期范围`)
}

// 获取检查结果中某类型的缺失区间列表
function getMissingRanges(type) {
  if (!integrityResult.value?.missingRanges) return []
  return integrityResult.value.missingRanges.filter(r => r.type === type)
}

// 格式化缺失区间显示：单天只显示日期，连续多天显示区间
// 传 startDate, endDate, days 三个参数（来自 MissingRange）
function formatRange(startDate, endDate, days) {
  if (!startDate) return ''
  if (days <= 1 || startDate === endDate) {
    return startDate
  }
  return `${startDate} ~ ${endDate}`
}

/* ==================== 完整性检查：快捷操作 ==================== */
// 数据类型 → 导入页路由名映射，用于「去导入」快捷跳转
const TYPE_TO_IMPORT_ROUTE = {
  BILL: 'BillImport',
  BANK_FLOW: 'BankReconciliation',
  EXCHANGE_RATE: 'ExchangeRateImport',
  EXTRA_COST: 'ExtraCostImport'
}

// 手动新增对话框（4 类数据共用，根据类型显示不同字段）
const quickAddVisible = ref(false)
const quickAddType = ref('') // BILL / BANK_FLOW / EXCHANGE_RATE / EXTRA_COST
const quickAddForm = ref({})
const quickAddLoading = ref(false)

// 打开手动新增对话框：根据数据类型初始化表单默认值
function openQuickAdd(type) {
  quickAddType.value = type
  const today = new Date().toISOString().slice(0, 10)
  if (type === 'BILL' || type === 'BANK_FLOW') {
    // 账单/银行流水共用同一实体，通过 source 区分；
    // 银行流水不需要 platform/shopId/fee/settleAmount 等账单特有字段，但仍保留键以便提交时结构统一
    quickAddForm.value = {
      source: type === 'BILL' ? 'PLATFORM' : 'BANK',
      orderNo: '', platform: '', shopId: '', currency: 'CNY',
      amount: null, fee: 0, settleAmount: null,
      orderTime: '', settleTime: '',
      batchNo: '' // 批次号可选，留空则后端自动生成
    }
    // 按表单 source 加载对应平台列表
    loadDialogPlatformOptions(type === 'BILL' ? 'PLATFORM' : 'BANK')
  } else if (type === 'EXCHANGE_RATE') {
    quickAddForm.value = {
      rateDate: today, fromCurrency: 'USD', toCurrency: 'CNY',
      rate: null, source: 'MANUAL'
    }
  } else if (type === 'EXTRA_COST') {
    const now = new Date()
    quickAddForm.value = {
      costType: 'OTHER', amount: 0, currency: 'CNY',
      period: `${now.getFullYear()}${String(now.getMonth() + 1).padStart(2, '0')}`,
      orderNo: '', payee: '', costDate: today, remark: '手工录入',
      batchNo: '', // 批次号可选
      status: 1 // 默认生效
    }
  }
  quickAddVisible.value = true
}

// 提交手动新增
async function submitQuickAdd() {
  const type = quickAddType.value
  const form = quickAddForm.value
  // 基础校验
  if (type === 'BILL' || type === 'BANK_FLOW') {
    const noLabel = type === 'BANK_FLOW' ? '交易流水号' : '订单号'
    const timeLabel = type === 'BANK_FLOW' ? '交易时间' : '下单时间'
    if (!form.orderNo) { ElMessage.warning(`请填写${noLabel}`); return }
    if (!form.orderTime) { ElMessage.warning(`请选择${timeLabel}`); return }
  } else if (type === 'EXCHANGE_RATE') {
    if (!form.rateDate) { ElMessage.warning('请选择汇率日期'); return }
    if (!form.rate) { ElMessage.warning('请填写汇率'); return }
  } else if (type === 'EXTRA_COST') {
    if (!form.costType) { ElMessage.warning('请选择费用类型'); return }
    if (form.amount === null || form.amount === undefined) { ElMessage.warning('请填写金额'); return }
    if (!form.costDate) { ElMessage.warning('请选择费用日期'); return }
  }
  quickAddLoading.value = true
  try {
    if (type === 'BILL' || type === 'BANK_FLOW') {
      await addOrder(form)
    } else if (type === 'EXCHANGE_RATE') {
      await addRate(form)
    } else if (type === 'EXTRA_COST') {
      await addCost(form)
    }
    ElMessage.success('新增成功')
    quickAddVisible.value = false
    // 新增后刷新对应 Tab 的数据列表（而非完整性检查）
    if (type === 'BILL' || type === 'BANK_FLOW') {
      loadOrders()
    } else if (type === 'EXTRA_COST') {
      loadCosts()
    } else if (type === 'EXCHANGE_RATE') {
      loadRates()
    }
  } catch (e) {
    // 错误已由请求拦截器统一处理
  } finally {
    quickAddLoading.value = false
  }
}

// 批量补零（仅额外费用）：对当前检查范围的缺失日期插入金额为 0 的占位记录
async function handleFillZero() {
  if (!integrityResult.value) return
  const { startDate, endDate } = integrityResult.value
  try {
    await ElMessageBox.confirm(
      `将对 ${startDate} ~ ${endDate} 范围内缺失的日期批量插入金额为 0 的额外费用占位记录，确认继续？`,
      '批量补零确认',
      { type: 'warning', confirmButtonText: '确认补零', cancelButtonText: '取消' }
    )
  } catch (_) { return }
  try {
    const res = await fillZeroCost(startDate, endDate)
    ElMessage.success(`补零完成，共新增 ${res.data} 条占位记录`)
    // 补零后自动重新检查
    await handleIntegrityCheck()
  } catch (e) {
    // 错误已由请求拦截器统一处理
  }
}

/* ==================== 批量编辑（合并批次号 / 修正分类字段） ==================== */
// 共享批量编辑对话框：order / cost / rate 三类数据共用
const batchEditVisible = ref(false)
const batchEditType = ref('') // order / cost / rate
const batchEditForm = ref({})
const batchEditLoading = ref(false)

// 打开批量编辑对话框
function openBatchEdit(type) {
  batchEditType.value = type
  // 初始化表单：所有字段留空，"留空不修改"语义
  if (type === 'order') {
    batchEditForm.value = {
      batchNo: '', platform: '', currency: '', shopId: ''
    }
  } else if (type === 'cost') {
    batchEditForm.value = {
      batchNo: '', period: '', currency: '', orderNo: '',
      payee: '', status: ''
    }
  } else if (type === 'rate') {
    batchEditForm.value = {
      fromCurrency: '', toCurrency: '', source: ''
    }
  }
  batchEditVisible.value = true
}

// 提交批量编辑：只提交非空字段，后端按"留空不修改"处理
async function submitBatchEdit() {
  const type = batchEditType.value
  const form = { ...batchEditForm.value }
  // 收集选中记录的 id 列表
  let ids = []
  if (type === 'order') ids = orderSelection.value.map(r => r.id)
  else if (type === 'cost') ids = costSelection.value.map(r => r.id)
  else if (type === 'rate') ids = rateSelection.value.map(r => r.id)
  if (ids.length === 0) {
    ElMessage.warning('请先选择要编辑的记录')
    return
  }
  // 移除空字段（后端按非空才更新，但前端也清理一下避免传空字符串）
  Object.keys(form).forEach(k => {
    if (form[k] === '' || form[k] === null || form[k] === undefined) delete form[k]
  })
  // 校验：至少修改一个字段
  if (Object.keys(form).length === 0) {
    ElMessage.warning('请至少填写一个要修改的字段')
    return
  }
  form.ids = ids
  // 二次确认（批量编辑是不可逆操作，会修改多条记录）
  try {
    await ElMessageBox.confirm(
      `确认批量编辑选中的 ${ids.length} 条记录？修改将立即生效且不可撤销`,
      '批量编辑确认',
      { type: 'warning', confirmButtonText: '确认修改', cancelButtonText: '取消' }
    )
  } catch (_) { return }
  batchEditLoading.value = true
  try {
    let res
    if (type === 'order') res = await batchUpdateOrders(form)
    else if (type === 'cost') res = await batchUpdateCosts(form)
    else if (type === 'rate') res = await batchUpdateRates(form)
    ElMessage.success(`批量编辑成功，共修改 ${res.data || ids.length} 条记录`)
    batchEditVisible.value = false
    // 清空跨页保留的选中状态，避免对同一批数据重复编辑
    if (type === 'order') orderTableRef.value?.clearSelection()
    else if (type === 'cost') costTableRef.value?.clearSelection()
    else if (type === 'rate') rateTableRef.value?.clearSelection()
    // 刷新对应列表
    if (type === 'order') loadOrders()
    else if (type === 'cost') loadCosts()
    else if (type === 'rate') loadRates()
  } catch (e) {
    // 错误已由请求拦截器统一处理
  } finally {
    batchEditLoading.value = false
  }
}

/* ==================== 数据导出（备份 / 外部使用） ==================== */
// 通用：将 blob 响应保存为 Excel 文件
function saveBlobAsExcel(blob, fileName) {
  const url = window.URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  window.URL.revokeObjectURL(url)
}

// 导出账单/银行流水（按当前筛选条件）
async function handleExportOrders() {
  try {
    const params = {
      source: orderFilter.source || undefined,
      orderNo: orderFilter.orderNo || undefined,
      platform: orderFilter.platform || undefined,
      currency: orderFilter.currency || undefined,
      batchNo: orderFilter.batchNo || undefined,
      startDate: orderPeriod.value?.startDate || undefined,
      endDate: orderPeriod.value?.endDate || undefined
    }
    const res = await exportOrders(params)
    saveBlobAsExcel(res, '账单银行流水.xlsx')
    ElMessage.success('导出成功')
  } catch (e) {
    // 错误已由请求拦截器统一处理
  }
}

// 导出额外费用
async function handleExportCosts() {
  try {
    const params = {
      costType: costFilter.costType || undefined,
      orderNo: costFilter.orderNo || undefined,
      period: costPeriod.value?.period || undefined,
      currency: costFilter.currency || undefined,
      batchNo: costFilter.batchNo || undefined,
      startDate: costPeriod.value?.startDate || undefined,
      endDate: costPeriod.value?.endDate || undefined
    }
    const res = await exportCosts(params)
    saveBlobAsExcel(res, '额外费用.xlsx')
    ElMessage.success('导出成功')
  } catch (e) {
    // 错误已由请求拦截器统一处理
  }
}

// 导出汇率
async function handleExportRates() {
  try {
    const params = {
      fromCurrency: rateFilter.fromCurrency || undefined,
      toCurrency: rateFilter.toCurrency || undefined,
      startDate: ratePeriod.value?.startDate || undefined,
      endDate: ratePeriod.value?.endDate || undefined
    }
    const res = await exportRates(params)
    saveBlobAsExcel(res, '汇率快照.xlsx')
    ElMessage.success('导出成功')
  } catch (e) {
    // 错误已由请求拦截器统一处理
  }
}

// 导出已核算利润报表（数据管理页快捷入口）
// 因利润报表按 period 过滤，弹窗让用户选择周期
const profitExportVisible = ref(false)
const profitExportForm = ref({ mode: 'MONTH', period: '', dateRange: [] })

function openProfitExport() {
  // 默认上个月
  const now = new Date()
  const lastMonth = new Date(now.getFullYear(), now.getMonth() - 1, 1)
  profitExportForm.value = {
    mode: 'MONTH',
    period: `${lastMonth.getFullYear()}${String(lastMonth.getMonth() + 1).padStart(2, '0')}`,
    dateRange: []
  }
  profitExportVisible.value = true
}

async function submitProfitExport() {
  try {
    let params = {}
    if (profitExportForm.value.mode === 'MONTH') {
      if (!profitExportForm.value.period) {
        ElMessage.warning('请选择核算月份')
        return
      }
      params = { period: profitExportForm.value.period }
    } else {
      if (!profitExportForm.value.dateRange || profitExportForm.value.dateRange.length !== 2) {
        ElMessage.warning('请选择日期范围')
        return
      }
      params = {
        startDate: profitExportForm.value.dateRange[0],
        endDate: profitExportForm.value.dateRange[1]
      }
    }
    const res = await exportProfitReport(params)
    const scope = params.period || `${params.startDate}_${params.endDate}`
    saveBlobAsExcel(res, `利润报表_${scope}.xlsx`)
    ElMessage.success('导出成功')
    profitExportVisible.value = false
  } catch (e) {
    // 错误已由请求拦截器统一处理
  }
}

// 跳转到对应导入页
function jumpToImportPage(type) {
  const routeName = TYPE_TO_IMPORT_ROUTE[type]
  if (!routeName) return
  integrityCheckDialogVisible.value = false
  router.push({ name: routeName })
}

// 导出当前类型的缺失日期清单为 CSV
function exportMissingRanges(type) {
  const ranges = type === 'ALL'
    ? (integrityResult.value?.missingRanges || [])
    : getMissingRanges(type)
  if (ranges.length === 0) {
    ElMessage.info('当前类型无缺失数据，无需导出')
    return
  }
  const typeLabel = type === 'ALL' ? '全部' : (TYPE_LABELS[type] || type)
  const header = '数据类型,缺失起始日期,缺失结束日期,缺失天数\n'
  const rows = ranges.map(r =>
    `${r.typeName || TYPE_LABELS[r.type] || r.type},${r.startDate},${r.endDate},${r.days}`
  ).join('\n')
  const blob = new Blob([`\ufeff${header}${rows}`], { type: 'text/csv;charset=utf-8' })
  const link = document.createElement('a')
  link.href = URL.createObjectURL(blob)
  link.download = `缺失数据清单_${typeLabel}_${integrityResult.value.startDate}_${integrityResult.value.endDate}.csv`
  link.click()
  URL.revokeObjectURL(link.href)
  ElMessage.success('缺失清单已导出')
}

onMounted(() => {
  // 支持从其他页面通过 query 跳转预填筛选条件
  const q = route.query
  if (q.tab === 'cost') {
    activeTab.value = 'cost'
    if (q.startDate && q.endDate) costPeriod.value = { mode: 'RANGE', period: '', startDate: q.startDate, endDate: q.endDate }
    loadCosts()
  } else if (q.tab === 'rate') {
    activeTab.value = 'rate'
    if (q.startDate && q.endDate) ratePeriod.value = { mode: 'RANGE', period: '', startDate: q.startDate, endDate: q.endDate }
    if (q.currency) rateFilter.fromCurrency = q.currency
    loadRates()
  } else if (q.tab === 'bank') {
    activeTab.value = 'order'
    orderFilter.source = 'BANK'
    if (q.startDate && q.endDate) orderPeriod.value = { mode: 'RANGE', period: '', startDate: q.startDate, endDate: q.endDate }
    loadPlatformOptions('BANK')
    loadOrders()
  } else {
    // 正常进入页面：恢复上次保存的 tab 状态，加载对应数据
    if (activeTab.value === 'cost') loadCosts()
    else if (activeTab.value === 'rate') loadRates()
    else {
      // 订单 tab：按当前模式/source 加载对应平台列表
      loadPlatformOptions(orderMode.value === 'reconcile' ? undefined : (orderFilter.source || undefined))
      loadOrders()
    }
  }
})
</script>

<template>
  <div>
    <el-alert
      v-if="!canEdit"
      type="info"
      :closable="false"
      style="margin-bottom: 16px"
      title="当前角色为只读，仅管理员（ADMIN）可编辑或删除数据。所有写操作均有审计日志。"
    />

    <!-- 顶部标题栏 + 右侧完整性检查按钮 -->
    <div class="page-card mb-16" style="display: flex; align-items: center; justify-content: space-between">
      <div style="font-size: 16px; font-weight: 600; color: #303133">数据管理</div>
      <div>
        <el-button
          type="success"
          plain
          size="default"
          icon="Download"
          @click="openProfitExport"
        >
          导出利润报表
        </el-button>
        <el-button
          type="primary"
          plain
          size="default"
          :loading="integrityCheckLoading"
          @click="openIntegrityCheck"
        >
          <el-icon style="margin-right: 4px"><DataAnalysis /></el-icon>
          数据完整性检查
        </el-button>
      </div>
    </div>

    <el-tabs v-model="activeTab" @tab-change="handleTabChange">
      <!-- ============ 账单/银行流水 ============ -->
      <el-tab-pane label="账单/银行流水" name="order">
        <!-- 操作模式切换 -->
        <div class="mb-16" style="display: flex; align-items: center; gap: 12px">
          <span style="color: #606266; font-size: 14px">操作模式：</span>
          <el-radio-group v-model="orderMode" @change="handleOrderModeChange">
            <el-radio-button value="standard">标准模式</el-radio-button>
            <el-radio-button value="reconcile">对账模式</el-radio-button>
          </el-radio-group>
          <el-tag v-if="orderMode === 'reconcile'" type="warning" size="small">
            对账模式：自动匹配平台账单与银行流水，生成差异报告
          </el-tag>
        </div>

        <!-- ===== 对账模式工具栏 ===== -->
        <div v-if="orderMode === 'reconcile'" class="page-card mb-16">
          <el-form :inline="true" size="default">
            <PeriodFilter v-model="reconcilePeriod" label="对账周期" @change="() => { orderPage.page = 1; loadOrders() }" />
            <el-form-item>
              <el-button
                v-if="canEdit"
                type="primary"
                :loading="autoReconciling"
                @click="handleAutoReconcile"
              >
                自动对账
              </el-button>
            </el-form-item>
            <el-form-item label="对账状态">
              <el-select
                v-model="reconcileStatusFilter"
                placeholder="全部状态"
                clearable
                style="width: 140px"
                @change="() => { orderPage.page = 1; loadOrders() }"
              >
                <el-option v-for="opt in reconcileStatusOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="平台">
              <el-select v-model="reconcileFilter.platform" placeholder="全部平台" clearable filterable style="width: 150px" @change="() => { orderPage.page = 1; loadOrders() }">
                <el-option v-for="opt in platformOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="币种">
              <el-select v-model="reconcileFilter.currency" placeholder="全部币种" clearable filterable style="width: 130px" @change="() => { orderPage.page = 1; loadOrders() }">
                <el-option v-for="opt in currencyOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="订单号">
              <el-input v-model="reconcileFilter.orderNo" placeholder="模糊查询" clearable style="width: 180px" @keyup.enter="() => { orderPage.page = 1; loadOrders() }" @clear="() => { orderPage.page = 1; loadOrders() }" />
            </el-form-item>
            <el-form-item>
              <el-button
                v-if="canEdit"
                type="success"
                plain
                :disabled="orderSelection.length === 0"
                @click="handleBatchResolve"
              >
                批量标记已处理（{{ orderSelection.length }}）
              </el-button>
              <el-button
                v-if="canEdit"
                type="warning"
                plain
                :disabled="orderSelection.length === 0"
                @click="handleBatchCancelReconcile"
              >
                批量取消对账（{{ orderSelection.length }}）
              </el-button>
            </el-form-item>
          </el-form>

          <!-- 对账汇总卡片 -->
          <div class="reconcile-summary-row" v-if="reconcileSummary.total > 0">
            <div class="reconcile-stat">
              <span class="stat-label">总记录</span>
              <span class="stat-num">{{ reconcileSummary.total }}</span>
            </div>
            <div class="reconcile-stat">
              <span class="stat-label">已匹配</span>
              <span class="stat-num" style="color:#67c23a">{{ reconcileSummary.matched }}</span>
            </div>
            <div class="reconcile-stat">
              <span class="stat-label">金额差异</span>
              <span class="stat-num" style="color:#f56c6c">{{ reconcileSummary.amountDiff }}</span>
            </div>
            <div class="reconcile-stat">
              <span class="stat-label">未到账</span>
              <span class="stat-num" style="color:#e6a23c">{{ reconcileSummary.platformOnly }}</span>
            </div>
            <div class="reconcile-stat">
              <span class="stat-label">不明入账</span>
              <span class="stat-num" style="color:#e6a23c">{{ reconcileSummary.bankOnly }}</span>
            </div>
            <div class="reconcile-stat">
              <span class="stat-label">未对账</span>
              <span class="stat-num" style="color:#909399">{{ reconcileSummary.pending }}</span>
            </div>
          </div>
        </div>

        <!-- ===== 标准模式筛选栏 ===== -->
        <div v-if="orderMode === 'standard'" class="page-card mb-16">
          <el-form :inline="true" size="default">
            <el-form-item label="数据来源">
              <el-select
                v-model="orderFilter.source"
                placeholder="全部"
                clearable
                style="width: 140px"
                @change="() => { loadPlatformOptions(orderFilter.source || undefined); orderFilter.platform = '' }"
              >
                <el-option label="平台账单" value="PLATFORM" />
                <el-option label="银行流水" value="BANK" />
              </el-select>
            </el-form-item>
            <el-form-item label="订单号">
              <el-input v-model="orderFilter.orderNo" placeholder="模糊查询" clearable style="width: 180px" />
            </el-form-item>
            <el-form-item label="平台">
              <el-select v-model="orderFilter.platform" placeholder="全部平台" clearable filterable style="width: 160px">
                <el-option v-for="opt in platformOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="币种">
              <el-select v-model="orderFilter.currency" placeholder="全部币种" clearable filterable style="width: 140px">
                <el-option v-for="opt in currencyOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="批次号">
              <el-input v-model="orderFilter.batchNo" placeholder="精确匹配" clearable style="width: 200px" />
            </el-form-item>
            <PeriodFilter v-model="orderPeriod" label="核算周期" @change="() => { orderPage.page = 1; loadOrders() }" />
            <el-form-item>
              <el-button type="primary" @click="handleOrderSearch">查询</el-button>
              <el-button @click="handleOrderReset">重置</el-button>
              <el-button
                v-if="canEdit"
                type="success"
                plain
                icon="Plus"
                @click="openQuickAdd('BILL')"
              >
                新增账单
              </el-button>
              <el-button
                v-if="canEdit"
                type="success"
                plain
                icon="Plus"
                @click="openQuickAdd('BANK_FLOW')"
              >
                新增银行流水
              </el-button>
              <el-button
                v-if="canEdit"
                type="warning"
                plain
                icon="Edit"
                :disabled="orderSelection.length === 0"
                @click="openBatchEdit('order')"
              >
                批量编辑（{{ orderSelection.length }}）
              </el-button>
              <el-button
                type="info"
                plain
                icon="Download"
                @click="handleExportOrders"
              >
                导出
              </el-button>
              <el-button
                v-if="canEdit"
                type="danger"
                plain
                :disabled="orderSelection.length === 0"
                @click="handleBatchDeleteOrders"
              >
                批量删除（{{ orderSelection.length }}）
              </el-button>
            </el-form-item>
          </el-form>
        </div>

        <div class="page-card">
          <el-table
            ref="orderTableRef"
            :data="orderData"
            v-loading="orderLoading"
            border
            stripe
            row-key="id"
            @selection-change="handleOrderSelectionChange"
          >
            <el-table-column v-if="canEdit" type="selection" width="45" :reserve-selection="true" />
            <el-table-column type="index" label="序号" width="70" :index="orderIndexMethod" />
            <el-table-column prop="orderNo" label="订单号" min-width="160" show-overflow-tooltip />
            <el-table-column v-if="orderMode === 'standard'" prop="source" label="来源" width="100">
              <template #default="{ row }">
                <el-tag :type="row.source === 'PLATFORM' ? 'primary' : 'warning'" size="small">
                  {{ row.source === 'PLATFORM' ? '平台账单' : '银行流水' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column v-if="orderMode === 'standard'" prop="platform" label="平台" width="110" />
            <el-table-column v-if="orderMode === 'standard'" prop="currency" label="币种" width="80" />
            <el-table-column v-if="orderMode === 'standard'" prop="amount" label="金额" width="140" align="right">
              <template #default="{ row }">{{ formatMoney(row.amount) }}<span v-if="row.currency" style="color:#909399;font-size:12px;margin-left:4px">{{ row.currency }}</span></template>
            </el-table-column>
            <el-table-column v-if="orderMode === 'standard'" prop="fee" label="手续费" width="120" align="right">
              <template #default="{ row }">{{ formatMoney(row.fee) }}<span v-if="row.currency" style="color:#909399;font-size:12px;margin-left:4px">{{ row.currency }}</span></template>
            </el-table-column>
            <el-table-column v-if="orderMode === 'standard'" prop="settleAmount" label="结算金额" width="140" align="right">
              <template #default="{ row }">{{ formatMoney(row.settleAmount) }}<span v-if="row.settleAmount !== null && row.settleAmount !== undefined" style="color:#67c23a;font-size:12px;margin-left:4px">CNY</span></template>
            </el-table-column>
            <!-- 对账模式：5 个 CNY 金额列集中展示，方便比对 -->
            <el-table-column v-if="orderMode === 'reconcile'" prop="platformAmountCny" label="账单金额" width="130" align="right">
              <template #default="{ row }">{{ formatMoney(row.platformAmountCny) }}<span v-if="row.platformAmountCny !== null && row.platformAmountCny !== undefined" style="color:#909399;font-size:12px;margin-left:4px">CNY</span></template>
            </el-table-column>
            <el-table-column v-if="orderMode === 'reconcile'" prop="platformFeeCny" label="平台手续费" width="120" align="right">
              <template #default="{ row }">{{ formatMoney(row.platformFeeCny) }}<span v-if="row.platformFeeCny !== null && row.platformFeeCny !== undefined" style="color:#909399;font-size:12px;margin-left:4px">CNY</span></template>
            </el-table-column>
            <el-table-column v-if="orderMode === 'reconcile'" prop="transferFeeCny" label="中转费" width="110" align="right">
              <template #default="{ row }">{{ formatMoney(row.transferFeeCny) }}<span v-if="row.transferFeeCny !== null && row.transferFeeCny !== undefined" style="color:#909399;font-size:12px;margin-left:4px">CNY</span></template>
            </el-table-column>
            <el-table-column v-if="orderMode === 'reconcile'" prop="bankReceivedCny" label="银行流水" width="130" align="right">
              <template #default="{ row }">{{ formatMoney(row.bankReceivedCny) }}<span v-if="row.bankReceivedCny !== null && row.bankReceivedCny !== undefined" style="color:#909399;font-size:12px;margin-left:4px">CNY</span></template>
            </el-table-column>
            <el-table-column v-if="orderMode === 'reconcile'" label="差值" width="130" align="right">
              <template #default="{ row }">
                <span v-if="row.reconcileDiff !== null && row.reconcileDiff !== undefined"
                      :style="{ color: row.reconcileStatus === 1 ? '#c0c4cc' : (Number(row.reconcileDiff) > 0 ? '#f56c6c' : '#e6a23c') }"
                      :class="{ 'diff-tiny': row.reconcileStatus === 1 }">
                  {{ formatMoney(row.reconcileDiff) }}
                </span>
                <span v-else style="color: #c0c4cc">-</span>
                <span v-if="row.reconcileDiff !== null && row.reconcileDiff !== undefined" style="color:#909399;font-size:12px;margin-left:4px">CNY</span>
              </template>
            </el-table-column>
            <el-table-column prop="orderTime" label="订单时间" width="160">
              <template #default="{ row }">{{ formatTime(row.orderTime) }}</template>
            </el-table-column>
            <el-table-column v-if="orderMode === 'standard'" prop="batchNo" label="批次号" width="180" show-overflow-tooltip />
            <!-- 对账模式：显示对账状态 -->
            <el-table-column v-if="orderMode === 'reconcile'" label="对账状态" width="110">
              <template #default="{ row }">
                <el-tag
                  :type="reconcileStatusTagType(row.reconcileStatus)"
                  size="small"
                >
                  {{ formatReconcileStatus(row.reconcileStatus) }}
                </el-tag>
              </template>
            </el-table-column>
            <!-- 操作列：标准模式显示编辑/删除，对账模式显示标记已处理/取消对账 -->
            <el-table-column label="操作" width="140" fixed="right">
              <template #default="{ row }">
                <template v-if="orderMode === 'standard'">
                  <el-button v-if="canEdit" type="primary" size="small" link @click="handleEditOrder(row)">编辑</el-button>
                  <el-button v-if="canEdit" type="danger" size="small" link @click="handleDeleteOrder(row)">删除</el-button>
                  <span v-if="!canEdit" style="color: #909399; font-size: 12px">只读</span>
                </template>
                <template v-else>
                  <el-button
                    v-if="canEdit && row.reconcileStatus !== 1"
                    type="success"
                    size="small"
                    link
                    @click="handleSingleResolve(row)"
                  >标记已处理</el-button>
                  <el-button
                    v-if="canEdit && row.reconcileStatus !== null && row.reconcileStatus !== 0"
                    type="warning"
                    size="small"
                    link
                    @click="handleSingleCancelReconcile(row)"
                  >取消对账</el-button>
                  <span v-if="!canEdit" style="color: #909399; font-size: 12px">只读</span>
                </template>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination
            class="mt-16"
            v-model:current-page="orderPage.page"
            v-model:page-size="orderPage.size"
            :total="orderTotal"
            :page-sizes="[10, 20, 50, 100]"
            layout="total, sizes, prev, pager, next, jumper"
            @size-change="handleOrderSizeChange"
            @current-change="handleOrderCurrentChange"
          />
        </div>
      </el-tab-pane>

      <!-- ============ 额外费用 ============ -->
      <el-tab-pane label="额外费用" name="cost">
        <div class="page-card mb-16">
          <el-form :inline="true" size="default">
            <el-form-item label="费用类型">
              <el-select v-model="costFilter.costType" placeholder="全部" clearable style="width: 140px">
                <el-option v-for="o in costTypeOptions" :key="o.value" :label="o.label" :value="o.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="订单号">
              <el-input v-model="costFilter.orderNo" placeholder="模糊查询" clearable style="width: 180px" />
            </el-form-item>
            <el-form-item label="币种">
              <el-select v-model="costFilter.currency" placeholder="全部币种" clearable filterable style="width: 140px">
                <el-option v-for="opt in currencyOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="批次号">
              <el-input v-model="costFilter.batchNo" placeholder="精确匹配" clearable style="width: 200px" />
            </el-form-item>
            <PeriodFilter v-model="costPeriod" label="核算周期" @change="() => { costPage.page = 1; loadCosts() }" />
            <el-form-item>
              <el-button type="primary" @click="handleCostSearch">查询</el-button>
              <el-button @click="handleCostReset">重置</el-button>
              <el-button
                v-if="canEdit"
                type="success"
                plain
                icon="Plus"
                @click="openQuickAdd('EXTRA_COST')"
              >
                新增费用
              </el-button>
              <el-button
                v-if="canEdit"
                type="warning"
                plain
                icon="Edit"
                :disabled="costSelection.length === 0"
                @click="openBatchEdit('cost')"
              >
                批量编辑（{{ costSelection.length }}）
              </el-button>
              <el-button
                type="info"
                plain
                icon="Download"
                @click="handleExportCosts"
              >
                导出
              </el-button>
              <el-button
                v-if="canEdit"
                type="danger"
                plain
                :disabled="costSelection.length === 0"
                @click="handleBatchDeleteCosts"
              >
                批量删除（{{ costSelection.length }}）
              </el-button>
            </el-form-item>
          </el-form>
        </div>

        <div class="page-card">
          <el-table
            ref="costTableRef"
            :data="costData"
            v-loading="costLoading"
            border
            stripe
            row-key="id"
            @selection-change="handleCostSelectionChange"
          >
            <el-table-column v-if="canEdit" type="selection" width="45" :reserve-selection="true" />
            <el-table-column type="index" label="序号" width="70" :index="costIndexMethod" />
            <el-table-column prop="costType" label="费用类型" width="110">
              <template #default="{ row }">{{ formatCostType(row.costType) }}</template>
            </el-table-column>
            <el-table-column prop="orderNo" label="订单号" min-width="150" show-overflow-tooltip>
              <template #default="{ row }">{{ row.orderNo || '（公共池）' }}</template>
            </el-table-column>
            <el-table-column prop="amount" label="金额" width="140" align="right">
              <template #default="{ row }">{{ formatMoney(row.amount) }}<span v-if="row.currency" style="color:#909399;font-size:12px;margin-left:4px">{{ row.currency }}</span></template>
            </el-table-column>
            <el-table-column prop="currency" label="币种" width="80" />
            <el-table-column prop="cnyAmount" label="CNY 金额" width="140" align="right">
              <template #default="{ row }">{{ formatMoney(row.cnyAmount) }}<span v-if="row.cnyAmount !== null && row.cnyAmount !== undefined" style="color:#67c23a;font-size:12px;margin-left:4px">CNY</span></template>
            </el-table-column>
            <el-table-column prop="period" label="核算周期" width="100" />
            <el-table-column prop="costDate" label="费用日期" width="120">
              <template #default="{ row }">{{ formatDate(row.costDate) }}</template>
            </el-table-column>
            <el-table-column prop="payee" label="收款方" width="120" show-overflow-tooltip />
            <el-table-column prop="remark" label="备注" min-width="150" show-overflow-tooltip />
            <el-table-column label="操作" width="140" fixed="right">
              <template #default="{ row }">
                <el-button v-if="canEdit" type="primary" size="small" link @click="handleEditCost(row)">编辑</el-button>
                <el-button v-if="canEdit" type="danger" size="small" link @click="handleDeleteCost(row)">删除</el-button>
                <span v-if="!canEdit" style="color: #909399; font-size: 12px">只读</span>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination
            class="mt-16"
            v-model:current-page="costPage.page"
            v-model:page-size="costPage.size"
            :total="costTotal"
            :page-sizes="[10, 20, 50, 100]"
            layout="total, sizes, prev, pager, next, jumper"
            @size-change="handleCostSizeChange"
            @current-change="handleCostCurrentChange"
          />
        </div>
      </el-tab-pane>

      <!-- ============ 汇率 ============ -->
      <el-tab-pane label="汇率" name="rate">
        <div class="page-card mb-16">
          <el-form :inline="true" size="default">
            <el-form-item label="源币种">
              <el-select v-model="rateFilter.fromCurrency" placeholder="全部源币种" clearable filterable style="width: 140px">
                <el-option v-for="opt in currencyOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="目标币种">
              <el-select v-model="rateFilter.toCurrency" placeholder="全部目标币种" clearable filterable style="width: 140px">
                <el-option v-for="opt in currencyOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
              </el-select>
            </el-form-item>
            <PeriodFilter v-model="ratePeriod" label="核算周期" @change="() => { ratePage.page = 1; loadRates() }" />
            <el-form-item>
              <el-button type="primary" @click="handleRateSearch">查询</el-button>
              <el-button @click="handleRateReset">重置</el-button>
              <el-button
                v-if="canEdit"
                type="success"
                plain
                icon="Plus"
                @click="openQuickAdd('EXCHANGE_RATE')"
              >
                新增汇率
              </el-button>
              <el-button
                v-if="canEdit"
                type="warning"
                plain
                icon="Edit"
                :disabled="rateSelection.length === 0"
                @click="openBatchEdit('rate')"
              >
                批量编辑（{{ rateSelection.length }}）
              </el-button>
              <el-button
                type="info"
                plain
                icon="Download"
                @click="handleExportRates"
              >
                导出
              </el-button>
              <el-button
                v-if="canEdit"
                type="danger"
                plain
                :disabled="rateSelection.length === 0"
                @click="handleBatchDeleteRates"
              >
                批量删除（{{ rateSelection.length }}）
              </el-button>
            </el-form-item>
          </el-form>
        </div>

        <div class="page-card">
          <el-table
            ref="rateTableRef"
            :data="rateData"
            v-loading="rateLoading"
            border
            stripe
            row-key="id"
            @selection-change="handleRateSelectionChange"
          >
            <el-table-column v-if="canEdit" type="selection" width="45" :reserve-selection="true" />
            <el-table-column type="index" label="序号" width="70" :index="rateIndexMethod" />
            <el-table-column prop="rateDate" label="汇率日期" width="120">
              <template #default="{ row }">{{ formatDate(row.rateDate) }}</template>
            </el-table-column>
            <el-table-column prop="fromCurrency" label="源币种" width="100" />
            <el-table-column prop="toCurrency" label="目标币种" width="100" />
            <el-table-column prop="rate" label="汇率" width="160" align="right">
              <template #default="{ row }">{{ Number(row.rate).toFixed(8) }}</template>
            </el-table-column>
            <el-table-column prop="source" label="来源" width="140" show-overflow-tooltip />
            <el-table-column prop="batchNo" label="批次号" width="180" show-overflow-tooltip />
            <el-table-column label="操作" width="140" fixed="right">
              <template #default="{ row }">
                <el-button v-if="canEdit" type="primary" size="small" link @click="handleEditRate(row)">编辑</el-button>
                <el-button v-if="canEdit" type="danger" size="small" link @click="handleDeleteRate(row)">删除</el-button>
                <span v-if="!canEdit" style="color: #909399; font-size: 12px">只读</span>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination
            class="mt-16"
            v-model:current-page="ratePage.page"
            v-model:page-size="ratePage.size"
            :total="rateTotal"
            :page-sizes="[10, 20, 50, 100]"
            layout="total, sizes, prev, pager, next, jumper"
            @size-change="handleRateSizeChange"
            @current-change="handleRateCurrentChange"
          />
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- ============ 账单/银行流水编辑对话框 ============ -->
    <!-- 根据 source 区分账单/银行流水，银行流水不显示 platform/shopId/fee/settleAmount 等账单特有字段 -->
    <el-dialog
      v-model="orderEditVisible"
      :title="orderEditForm.source === 'BANK' ? '编辑银行流水' : '编辑账单'"
      width="600px"
    >
      <el-form :model="orderEditForm" label-width="100px">
        <el-form-item :label="orderEditForm.source === 'BANK' ? '交易流水号' : '订单号'" required>
          <el-input v-model="orderEditForm.orderNo" />
        </el-form-item>
        <el-form-item label="平台">
          <el-select v-model="orderEditForm.platform" placeholder="请选择平台" clearable filterable style="width: 100%">
            <el-option v-for="opt in dialogPlatformOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="店铺ID">
          <el-input v-model="orderEditForm.shopId" />
        </el-form-item>
        <el-form-item label="币种">
          <el-select v-model="orderEditForm.currency" placeholder="请选择币种" clearable filterable style="width: 100%">
            <el-option v-for="opt in currencyOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="金额">
          <el-input-number v-model="orderEditForm.amount" :precision="2" :step="0.01" style="width: 100%" />
        </el-form-item>
        <el-form-item label="手续费">
          <el-input-number v-model="orderEditForm.fee" :precision="2" :step="0.01" style="width: 100%" />
        </el-form-item>
        <el-form-item label="结算金额">
          <el-input-number v-model="orderEditForm.settleAmount" :precision="2" :step="0.01" style="width: 100%" />
        </el-form-item>
        <el-form-item :label="orderEditForm.source === 'BANK' ? '交易时间' : '下单时间'">
          <el-date-picker
            v-model="orderEditForm.orderTime"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item :label="orderEditForm.source === 'BANK' ? '入账时间' : '结算时间'">
          <el-date-picker
            v-model="orderEditForm.settleTime"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="批次号">
          <el-input v-model="orderEditForm.batchNo" placeholder="可选，可单独编辑" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="orderEditVisible = false">取消</el-button>
        <el-button type="primary" @click="submitOrderEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- ============ 额外费用编辑对话框 ============ -->
    <el-dialog v-model="costEditVisible" title="编辑额外费用" width="600px">
      <el-form :model="costEditForm" label-width="100px">
        <el-form-item label="费用类型">
          <el-select v-model="costEditForm.costType" style="width: 100%">
            <el-option v-for="o in costTypeOptions" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="金额">
          <el-input-number v-model="costEditForm.amount" :precision="2" :step="0.01" style="width: 100%" />
        </el-form-item>
        <el-form-item label="币种">
          <el-select v-model="costEditForm.currency" placeholder="请选择币种" clearable filterable style="width: 100%">
            <el-option v-for="opt in currencyOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="核算周期">
          <el-input v-model="costEditForm.period" placeholder="如 202607" />
        </el-form-item>
        <el-form-item label="订单号">
          <el-input v-model="costEditForm.orderNo" placeholder="可选，公共池费用留空" />
        </el-form-item>
        <el-form-item label="收款方">
          <el-input v-model="costEditForm.payee" />
        </el-form-item>
        <el-form-item label="费用日期">
          <el-date-picker
            v-model="costEditForm.costDate"
            type="date"
            value-format="YYYY-MM-DD"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="批次号">
          <el-input v-model="costEditForm.batchNo" placeholder="可选，可单独编辑" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="costEditForm.status" placeholder="请选择状态" style="width: 100%">
            <el-option v-for="opt in costStatusOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="costEditForm.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="costEditVisible = false">取消</el-button>
        <el-button type="primary" @click="submitCostEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- ============ 汇率编辑对话框 ============ -->
    <el-dialog v-model="rateEditVisible" title="编辑汇率" width="500px">
      <el-form :model="rateEditForm" label-width="100px">
        <el-form-item label="汇率日期">
          <el-date-picker
            v-model="rateEditForm.rateDate"
            type="date"
            value-format="YYYY-MM-DD"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="源币种">
          <el-select v-model="rateEditForm.fromCurrency" placeholder="请选择源币种" clearable filterable style="width: 100%">
            <el-option v-for="opt in currencyOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标币种">
          <el-select v-model="rateEditForm.toCurrency" placeholder="请选择目标币种" clearable filterable style="width: 100%">
            <el-option v-for="opt in currencyOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="汇率">
          <el-input-number v-model="rateEditForm.rate" :precision="8" :step="0.0001" style="width: 100%" />
        </el-form-item>
        <el-form-item label="来源">
          <el-select v-model="rateEditForm.source" placeholder="请选择来源" clearable filterable style="width: 100%">
            <el-option v-for="opt in sourceOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rateEditVisible = false">取消</el-button>
        <el-button type="primary" @click="submitRateEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 数据完整性检查对话框（日期选择 + 结果展示合一） -->
    <el-dialog
      v-model="integrityCheckDialogVisible"
      title="数据完整性检查"
      width="780px"
      class="integrity-check-dialog"
      align-center
    >
      <!-- 日期选择区（始终显示，固定不滚动） -->
      <div class="integrity-toolbar mb-16" style="display: flex; align-items: center; gap: 12px; flex-wrap: wrap">
        <el-radio-group v-model="integrityMode" size="small">
          <el-radio-button value="month">按月份</el-radio-button>
          <el-radio-button value="range">按日期范围</el-radio-button>
        </el-radio-group>
        <el-date-picker
          v-if="integrityMode === 'month'"
          v-model="integrityMonth"
          type="month"
          format="YYYY年MM月"
          value-format="YYYYMM"
          placeholder="选择月份"
          style="width: 160px"
        />
        <el-date-picker
          v-else
          v-model="integrityDateRange"
          type="daterange"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          value-format="YYYY-MM-DD"
          style="width: 280px"
        />
        <el-button
          type="primary"
          :loading="integrityCheckLoading"
          @click="handleIntegrityCheck"
        >
          开始检查
        </el-button>
      </div>

      <!-- 可滚动内容区（结果区 / 占位提示） -->
      <div class="integrity-scroll">
      <!-- 结果区 -->
      <div v-if="integrityResult">
        <el-divider style="margin: 12px 0" />

        <!-- 顶部摘要 -->
        <el-descriptions :column="3" border size="small" style="margin-bottom: 16px">
          <el-descriptions-item label="检查范围">
            {{ integrityResult.startDate }} ~ {{ integrityResult.endDate }}
          </el-descriptions-item>
          <el-descriptions-item label="总天数">
            {{ integrityResult.totalDays }} 天
          </el-descriptions-item>
          <el-descriptions-item label="核算影响">
            <el-tag :type="integrityResult.blocking ? 'danger' : 'success'" size="small">
              {{ integrityResult.blocking ? '阻断核算' : '可核算' }}
            </el-tag>
          </el-descriptions-item>
        </el-descriptions>

        <!-- 阻断提示 -->
        <el-alert
          v-if="integrityResult.blocking"
          type="error"
          :closable="false"
          style="margin-bottom: 16px"
          title="数据不完整，利润核算将被阻断"
        >
          <template #default>
            <div style="line-height: 1.6">
              平台账单或汇率数据缺失会导致无法折算 CNY 金额。请切换到对应数据类型，点击「查看该日期数据」跳转补充数据后重新核算。
            </div>
          </template>
        </el-alert>

        <!-- 数据类型切换按钮 -->
        <div style="margin-bottom: 12px">
          <el-radio-group v-model="integrityActiveType" size="small">
            <el-radio-button value="ALL">
              全部汇总
              <el-badge
                v-if="integrityResult.missingRanges && integrityResult.missingRanges.length > 0"
                :value="integrityResult.missingRanges.length"
                :max="99"
                style="margin-left: 4px"
              />
            </el-radio-button>
            <el-radio-button v-for="t in ['BILL','BANK_FLOW','EXCHANGE_RATE','EXTRA_COST']" :key="t" :value="t">
              {{ TYPE_LABELS[t] }}
              <el-badge
                v-if="getMissingRanges(t).length > 0"
                :value="getMissingRanges(t).length"
                :max="99"
                style="margin-left: 4px"
              />
            </el-radio-button>
          </el-radio-group>
        </div>

        <!-- 全部汇总视图 -->
        <div v-if="integrityActiveType === 'ALL'">
          <el-table :data="Object.entries(integrityResult.summary).map(([k, v]) => ({ type: k, ...v }))" border stripe size="small">
            <el-table-column label="数据类型" width="120">
              <template #default="{ row }">
                <el-tag :type="row.missingDays > 0 ? 'danger' : 'success'" size="small">{{ row.typeName }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="coveredDays" label="已覆盖天数" width="100" align="center" />
            <el-table-column prop="missingDays" label="缺失天数" width="100" align="center">
              <template #default="{ row }">
                <span :style="row.missingDays > 0 ? 'color: #f56c6c; font-weight: 600' : ''">{{ row.missingDays }}</span>
              </template>
            </el-table-column>
            <el-table-column label="缺失日期" min-width="200">
              <template #default="{ row }">
                <span v-if="row.missingDays === 0" style="color: #67c23a">完整覆盖</span>
                <span v-else style="color: #f56c6c">{{ formatRange(row.firstMissing, row.lastMissing, row.missingDays) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="120" align="center">
              <template #default="{ row }">
                <el-button
                  v-if="row.missingDays > 0"
                  type="primary"
                  size="small"
                  link
                  @click="integrityActiveType = row.type"
                >
                  查看明细
                </el-button>
              </template>
            </el-table-column>
          </el-table>

          <!-- 全部完整时的成功提示 -->
          <el-result
            v-if="!integrityResult.blocking && (!integrityResult.missingRanges || integrityResult.missingRanges.length === 0)"
            icon="success"
            title="数据完整"
            sub-title="所选日期范围内所有数据类型均已覆盖，可以放心进行利润核算"
          />
        </div>

        <!-- 单类型明细视图（平台账单/银行流水/汇率/额外费用） -->
        <div v-else>
          <!-- 快捷操作区：手动新增 / 批量补零(仅额外费用) / 去导入 / 导出缺失清单 -->
          <div class="integrity-quick-actions" style="margin-bottom: 12px; display: flex; gap: 8px; flex-wrap: wrap">
            <el-button type="primary" size="small" @click="openQuickAdd(integrityActiveType)">
              <el-icon style="margin-right: 4px"><Plus /></el-icon>手动新增
            </el-button>
            <el-button
              v-if="integrityActiveType === 'EXTRA_COST'"
              type="warning"
              size="small"
              @click="handleFillZero"
            >
              <el-icon style="margin-right: 4px"><MagicStick /></el-icon>批量补零
            </el-button>
            <el-button size="small" @click="jumpToImportPage(integrityActiveType)">
              <el-icon style="margin-right: 4px"><Upload /></el-icon>去导入
            </el-button>
            <el-button size="small" plain @click="exportMissingRanges(integrityActiveType)">
              <el-icon style="margin-right: 4px"><Download /></el-icon>导出缺失清单
            </el-button>
          </div>

          <!-- 汇率类型额外显示多币种缺失 -->
          <div v-if="integrityActiveType === 'EXCHANGE_RATE' && integrityResult.currencyRateMissing && integrityResult.currencyRateMissing.length > 0" style="margin-bottom: 16px">
            <el-alert type="warning" :closable="false" style="margin-bottom: 8px">
              以下币种在订单涉及的日期缺少到 CNY 的汇率，无法折算人民币金额。
            </el-alert>
            <el-table :data="integrityResult.currencyRateMissing" border stripe size="small" style="margin-bottom: 12px">
              <el-table-column prop="currency" label="币种" width="80" />
              <el-table-column prop="targetCurrency" label="目标币种" width="90" />
              <el-table-column prop="missingDays" label="缺失天数" width="90" align="center">
                <template #default="{ row }">
                  <el-tag type="danger" size="small">{{ row.missingDays }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="requiredDays" label="涉及订单天数" width="120" align="center" />
              <el-table-column label="缺失日期" min-width="180">
                <template #default="{ row }">
                  <span v-for="(r, i) in row.ranges" :key="i" style="color: #f56c6c; display: block; font-size: 12px">
                    {{ formatRange(r.startDate, r.endDate, r.days) }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="130">
                <template #default="{ row }">
                  <el-button type="primary" size="small" link @click="jumpToMissingData('EXCHANGE_RATE', row.ranges[0])">
                    查看汇率数据
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <!-- 当前类型的缺失区间列表 -->
          <div v-if="getMissingRanges(integrityActiveType).length > 0">
            <el-table :data="getMissingRanges(integrityActiveType)" border stripe size="small">
              <el-table-column label="缺失日期" min-width="220">
                <template #default="{ row }">
                  <span style="color: #f56c6c; font-weight: 500">{{ formatRange(row.startDate, row.endDate, row.days) }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="days" label="天数" width="80" align="center">
                <template #default="{ row }">
                  <el-tag type="danger" size="small">{{ row.days }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="160">
                <template #default="{ row }">
                  <el-button type="primary" size="small" link @click="jumpToMissingData(integrityActiveType, row)">
                    查看该日期数据
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
          <el-empty
            v-else
            description="该类型数据完整覆盖，无缺失"
            :image-size="60"
          />
        </div>
      </div>

      <!-- 未检查时的占位提示 -->
      <el-empty
        v-else
        description="选择日期范围后点击「开始检查」"
        :image-size="60"
      />
      </div>
      <!-- /可滚动内容区 -->

      <template #footer>
        <el-button @click="integrityCheckDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 手动新增对话框（完整性检查快捷操作） -->
    <el-dialog
      v-model="quickAddVisible"
      :title="`手动新增${quickAddType === 'BILL' ? '平台账单' : quickAddType === 'BANK_FLOW' ? '银行流水' : quickAddType === 'EXCHANGE_RATE' ? '汇率' : '额外费用'}`"
      width="560px"
      align-center
    >
      <!-- 账单/银行流水表单 -->
      <el-form
        v-if="quickAddType === 'BILL' || quickAddType === 'BANK_FLOW'"
        :model="quickAddForm"
        label-width="100px"
      >
        <el-form-item :label="quickAddType === 'BANK_FLOW' ? '交易流水号' : '订单号'" required>
          <el-input v-model="quickAddForm.orderNo" placeholder="必填" />
        </el-form-item>
        <el-form-item label="平台">
          <el-select v-model="quickAddForm.platform" placeholder="请选择平台" clearable filterable style="width: 100%">
            <el-option v-for="opt in dialogPlatformOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="店铺ID">
          <el-input v-model="quickAddForm.shopId" />
        </el-form-item>
        <el-form-item label="币种">
          <el-select v-model="quickAddForm.currency" placeholder="请选择币种" filterable style="width: 100%">
            <el-option v-for="opt in currencyOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="金额">
          <el-input-number v-model="quickAddForm.amount" :precision="2" :step="0.01" style="width: 100%" />
        </el-form-item>
        <el-form-item label="手续费">
          <el-input-number v-model="quickAddForm.fee" :precision="2" :step="0.01" style="width: 100%" />
        </el-form-item>
        <el-form-item label="结算金额">
          <el-input-number v-model="quickAddForm.settleAmount" :precision="2" :step="0.01" style="width: 100%" />
        </el-form-item>
        <el-form-item :label="quickAddType === 'BANK_FLOW' ? '交易时间' : '下单时间'" required>
          <el-date-picker
            v-model="quickAddForm.orderTime"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            placeholder="选择时间"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item :label="quickAddType === 'BANK_FLOW' ? '入账时间' : '结算时间'">
          <el-date-picker
            v-model="quickAddForm.settleTime"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            placeholder="可选"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="批次号">
          <el-input v-model="quickAddForm.batchNo" placeholder="可选，留空则后端自动生成" />
        </el-form-item>
      </el-form>

      <!-- 汇率表单 -->
      <el-form
        v-else-if="quickAddType === 'EXCHANGE_RATE'"
        :model="quickAddForm"
        label-width="100px"
      >
        <el-form-item label="汇率日期" required>
          <el-date-picker
            v-model="quickAddForm.rateDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择日期"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="源币种" required>
          <el-select v-model="quickAddForm.fromCurrency" placeholder="请选择源币种" filterable style="width: 100%">
            <el-option v-for="opt in currencyOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标币种" required>
          <el-select v-model="quickAddForm.toCurrency" placeholder="请选择目标币种" filterable style="width: 100%">
            <el-option v-for="opt in currencyOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="汇率" required>
          <el-input-number v-model="quickAddForm.rate" :precision="8" :step="0.0001" style="width: 100%" />
        </el-form-item>
        <el-form-item label="来源">
          <el-select v-model="quickAddForm.source" placeholder="请选择来源" clearable filterable style="width: 100%">
            <el-option v-for="opt in sourceOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
      </el-form>

      <!-- 额外费用表单 -->
      <el-form
        v-else-if="quickAddType === 'EXTRA_COST'"
        :model="quickAddForm"
        label-width="100px"
      >
        <el-form-item label="费用类型" required>
          <el-select v-model="quickAddForm.costType" style="width: 100%">
            <el-option v-for="opt in costTypeOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="金额" required>
          <el-input-number v-model="quickAddForm.amount" :precision="2" :step="0.01" style="width: 100%" />
        </el-form-item>
        <el-form-item label="币种">
          <el-select v-model="quickAddForm.currency" placeholder="请选择币种" filterable style="width: 100%">
            <el-option v-for="opt in currencyOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="核算周期">
          <el-input v-model="quickAddForm.period" placeholder="如 202607" />
        </el-form-item>
        <el-form-item label="订单号">
          <el-input v-model="quickAddForm.orderNo" placeholder="可选，公共池费用留空" />
        </el-form-item>
        <el-form-item label="收款方">
          <el-input v-model="quickAddForm.payee" />
        </el-form-item>
        <el-form-item label="费用日期" required>
          <el-date-picker
            v-model="quickAddForm.costDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择日期"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="批次号">
          <el-input v-model="quickAddForm.batchNo" placeholder="可选，留空则后端自动生成" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="quickAddForm.status" placeholder="请选择状态" style="width: 100%">
            <el-option v-for="opt in costStatusOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="quickAddForm.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="quickAddVisible = false">取消</el-button>
        <el-button type="primary" :loading="quickAddLoading" @click="submitQuickAdd">保存</el-button>
      </template>
    </el-dialog>

    <!-- 批量编辑对话框（order/cost/rate 三类共用） -->
    <el-dialog
      v-model="batchEditVisible"
      :title="batchEditType === 'order' ? '批量编辑账单/银行流水'
        : batchEditType === 'cost' ? '批量编辑额外费用'
        : '批量编辑汇率'"
      width="520px"
    >
      <el-alert
        type="info" :closable="false" style="margin-bottom: 16px"
        title="留空不修改：仅填写需要修改的字段，未填字段保持原值不变"
      />
      <el-form :model="batchEditForm" label-width="90px" v-if="batchEditType === 'order'">
        <el-form-item label="批次号">
          <el-input v-model="batchEditForm.batchNo" placeholder="留空不修改，填写则合并为新批次" />
        </el-form-item>
        <el-form-item label="平台">
          <el-input v-model="batchEditForm.platform" placeholder="留空不修改" />
        </el-form-item>
        <el-form-item label="币种">
          <el-select v-model="batchEditForm.currency" placeholder="留空不修改" clearable filterable style="width: 100%">
            <el-option v-for="opt in currencyOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="店铺 ID">
          <el-input v-model="batchEditForm.shopId" placeholder="留空不修改" />
        </el-form-item>
      </el-form>
      <el-form :model="batchEditForm" label-width="90px" v-else-if="batchEditType === 'cost'">
        <el-form-item label="批次号">
          <el-input v-model="batchEditForm.batchNo" placeholder="留空不修改，填写则合并为新批次" />
        </el-form-item>
        <el-form-item label="核算周期">
          <el-input v-model="batchEditForm.period" placeholder="留空不修改，如 202607" />
        </el-form-item>
        <el-form-item label="币种">
          <el-select v-model="batchEditForm.currency" placeholder="留空不修改" clearable filterable style="width: 100%">
            <el-option v-for="opt in currencyOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="订单号">
          <el-input v-model="batchEditForm.orderNo" placeholder="留空不修改" />
        </el-form-item>
        <el-form-item label="收款方">
          <el-input v-model="batchEditForm.payee" placeholder="留空不修改" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="batchEditForm.status" placeholder="留空不修改" clearable style="width: 100%">
            <el-option :value="1" label="生效" />
            <el-option :value="0" label="已作废" />
          </el-select>
        </el-form-item>
      </el-form>
      <el-form :model="batchEditForm" label-width="90px" v-else-if="batchEditType === 'rate'">
        <el-form-item label="源币种">
          <el-select v-model="batchEditForm.fromCurrency" placeholder="留空不修改" clearable filterable style="width: 100%">
            <el-option v-for="opt in currencyOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标币种">
          <el-select v-model="batchEditForm.toCurrency" placeholder="留空不修改" clearable filterable style="width: 100%">
            <el-option v-for="opt in currencyOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="来源">
          <el-input v-model="batchEditForm.source" placeholder="留空不修改，如 央行/第三方" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="batchEditVisible = false">取消</el-button>
        <el-button type="primary" :loading="batchEditLoading" @click="submitBatchEdit">确认修改</el-button>
      </template>
    </el-dialog>

    <!-- 利润报表导出对话框（数据管理页快捷入口） -->
    <el-dialog v-model="profitExportVisible" title="导出已核算利润报表" width="460px">
      <el-form :model="profitExportForm" label-width="90px">
        <el-form-item label="核算模式">
          <el-radio-group v-model="profitExportForm.mode">
            <el-radio value="MONTH">月份模式</el-radio>
            <el-radio value="RANGE">日期范围</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="核算月份" v-if="profitExportForm.mode === 'MONTH'">
          <el-date-picker
            v-model="profitExportForm.period"
            type="month"
            value-format="YYYYMM"
            placeholder="选择月份"
            style="width: 180px"
          />
        </el-form-item>
        <el-form-item label="日期范围" v-else>
          <el-date-picker
            v-model="profitExportForm.dateRange"
            type="daterange"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            value-format="YYYY-MM-DD"
            style="width: 260px"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="profitExportVisible = false">取消</el-button>
        <el-button type="primary" @click="submitProfitExport">导出 Excel</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.diff-tiny {
  font-size: 12px;
}
.reconcile-summary-row {
  display: flex;
  gap: 24px;
  margin-top: 16px;
  padding: 16px 20px;
  background: #f5f7fa;
  border-radius: 6px;
  flex-wrap: wrap;
}
.reconcile-stat {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}
.reconcile-stat .stat-label {
  font-size: 12px;
  color: #909399;
}
.reconcile-stat .stat-num {
  font-size: 22px;
  font-weight: 600;
  color: #303133;
}

/* 数据完整性检查对话框：内容少时自适应，内容多时封顶滚动，仅结果区滚动 */
:deep(.integrity-check-dialog) {
  max-height: min(80vh, 720px);
  display: flex;
  flex-direction: column;
  margin: 0 auto;
}
:deep(.integrity-check-dialog .el-dialog__body) {
  flex: 1 1 auto;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.integrity-toolbar {
  flex-shrink: 0;
}
.integrity-scroll {
  flex: 1 1 auto;
  min-height: 240px;
  overflow-y: auto;
  padding-right: 8px;
}
</style>
