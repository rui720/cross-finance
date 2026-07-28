<script setup>
// 操作审计日志页：按用户名与时间范围查询日志、查看详情、批量删除、撤销操作
import { reactive, ref, watch, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { pageAuditLog, batchDeleteAuditLog, undoAuditLog } from '@/api/system'
import { usePageState } from '@/composables/usePageState'

// 页面状态保持（搜索条件、页号切换页面后自动恢复）
const { loadField, saveField } = usePageState('AuditLog')

/**
 * 操作 key → 撤销按钮名称映射（4字命名，保证前端按钮宽度一致）
 * key 从 operation 字段 "中文描述(ClassName.methodName)" 中提取
 */
const UNDO_BUTTON_MAP = {
  // 用户管理
  'SysUserController.add': '撤销新增',
  'SysUserController.update': '撤销修改',
  'SysUserController.delete': '撤销删除',
  'SysUserController.recover': '撤销恢复',
  'SysUserController.assignRoles': '撤销分配',
  // 分摊规则
  'ModelConfigController.create': '撤销新增',
  'ModelConfigController.update': '撤销修改',
  'ModelConfigController.delete': '撤销删除',
  'ModelConfigController.enable': '撤销启用',
  'ModelConfigController.disable': '撤销禁用',
  'ModelConfigController.toggleEnabled': '撤销切换',
  // 数据管理 - 账单/银行流水
  'DataManagementController.updateOrder': '撤销修改',
  'DataManagementController.deleteOrder': '撤销删除',
  'DataManagementController.batchDeleteOrders': '撤销删除',
  // 数据管理 - 额外费用
  'DataManagementController.updateCost': '撤销修改',
  'DataManagementController.deleteCost': '撤销删除',
  'DataManagementController.batchDeleteCosts': '撤销删除',
  // 数据管理 - 汇率
  'DataManagementController.updateRate': '撤销修改',
  'DataManagementController.deleteRate': '撤销删除',
  'DataManagementController.batchDeleteRates': '撤销删除',
  // 数据导入
  'DataImportController.importBill': '撤销导入',
  'DataImportController.importBankFlow': '撤销导入',
  // 银行流水对账
  'BankFlowController.reconcile': '撤销对账',
  'BankFlowController.cancelReconcile': '撤销对账',
  // 利润核算
  'ProfitCalcController.calculate': '撤销核算',
  'ProfitCalcController.calculateByRange': '撤销核算',
  // 审计日志
  'SysAuditLogController.delete': '撤销删除',
  'SysAuditLogController.batchDelete': '撤销删除'
}

/** 从 operation 字段中提取操作 key（ClassName.methodName） */
function extractOpKey(operation) {
  if (!operation) return ''
  const start = operation.indexOf('(')
  const end = operation.lastIndexOf(')')
  if (start >= 0 && end > start) {
    return operation.substring(start + 1, end)
  }
  return operation
}

/** 获取撤销按钮名称，不可撤销时返回 null */
function getUndoLabel(operation) {
  const key = extractOpKey(operation)
  return UNDO_BUTTON_MAP[key] || null
}

/** 判断是否可撤销：有撤销名称 && 操作成功 && 未撤销 && 有旧值快照 */
function canUndo(row) {
  return getUndoLabel(row.operation) && row.status === 1 && row.undone !== 1 && row.oldValue
}

/**
 * 解析 oldValue，返回结构化展示信息。
 * 根据 oldValue 中的特征字段判断操作类型，返回 { type, title, items, table } 三种展示模式之一。
 */
function parseOldValue(rawOldValue) {
  if (!rawOldValue) return null
  let json
  try {
    json = JSON.parse(rawOldValue)
  } catch {
    return null
  }

  // 1. 导入操作：含 batchNo + 统计字段
  if (json.batchNo) {
    const items = []
    if (json.fileName) items.push({ label: '文件名', value: json.fileName })
    items.push({ label: '批次号', value: json.batchNo })
    if (json.totalRows !== undefined) items.push({ label: '总行数', value: json.totalRows })
    if (json.successCount !== undefined) items.push({ label: '成功导入', value: json.successCount + ' 条' })
    if (json.failedCount !== undefined) items.push({ label: '失败行数', value: json.failedCount + ' 条' })
    if (json.duplicateCount !== undefined && json.duplicateCount > 0) {
      items.push({ label: '重复行数', value: json.duplicateCount + ' 条' })
    }
    if (json.wholeTableDuplicate === true) {
      items.push({ label: '整表重复', value: '是（该表已导入过）' })
    }
    return { type: 'items', title: '导入详情', items }
  }

  // 2. 删除操作（含 entities 字段）
  if (json.entities) {
    return { type: 'table', title: '被删除记录（共 ' + json.entities.length + ' 条）', rows: json.entities }
  }
  if (json.entity) {
    return { type: 'items', title: '被删除记录详情', items: objectToItems(json.entity) }
  }

  // 3. 新增操作：含 newId
  if (json.newId !== undefined) {
    return { type: 'items', title: '新增记录', items: [{ label: '新记录 ID', value: json.newId }] }
  }

  // 4. 核算操作：含 period
  if (json.period) {
    return { type: 'items', title: '核算周期', items: [{ label: '周期', value: json.period }] }
  }

  // 5. 删除操作（旧格式，仅含 id/ids 无 entity/entities）
  if (json.id !== undefined) {
    return { type: 'items', title: '被删除记录', items: [{ label: '记录 ID', value: json.id }] }
  }
  if (json.ids) {
    return { type: 'items', title: '被删除记录', items: [{ label: '记录 IDs', value: json.ids.join(', ') }] }
  }

  // 6. update/状态变更：oldValue 直接是实体对象或实体数组
  if (Array.isArray(json)) {
    return { type: 'table', title: '修改前数据（共 ' + json.length + ' 条）', rows: json }
  }
  if (typeof json === 'object' && Object.keys(json).length > 0) {
    return { type: 'items', title: '修改前数据', items: objectToItems(json) }
  }

  return null
}

/** 将对象转为 { label, value } 列表，跳过 null/空值和敏感字段 */
function objectToItems(obj) {
  const sensitiveKeys = ['password', 'deleted']
  const labelMap = {
    id: 'ID', orderNo: '订单号', platform: '平台', shopId: '店铺ID',
    currency: '币种', amount: '金额', fee: '手续费', settleAmount: '结算金额',
    orderTime: '订单时间', settleTime: '结算时间', cleanStatus: '清洗状态',
    reconcileStatus: '对账状态', batchNo: '批次号', source: '数据来源',
    username: '用户名', employeeNo: '工号', realName: '真实姓名',
    phone: '手机号', email: '邮箱',
    status: '状态', deptId: '部门ID', roleIds: '角色ID',
    ruleName: '规则名称', ruleType: '规则类型', enabled: '启用状态',
    formula: '公式', description: '描述',
    costType: '费用类型', period: '核算周期', payee: '收款方',
    costDate: '费用日期', remark: '备注',
    rateDate: '汇率日期', fromCurrency: '源币种', toCurrency: '目标币种',
    rate: '汇率',
    applyAmount: '申请金额', payeeAccount: '收款账号',
    operation: '操作描述', createTime: '创建时间'
  }
  return Object.entries(obj)
    .filter(([k, v]) => !sensitiveKeys.includes(k) && v !== null && v !== undefined && v !== '')
    .map(([k, v]) => ({
      label: labelMap[k] || k,
      value: typeof v === 'string' && v.length > 10 && v.includes('T')
        ? v.replace('T', ' ').split('.')[0]
        : String(v)
    }))
}

/** 实体表格列配置：动态从第一行数据提取字段 */
function getEntityColumns(rows) {
  if (!rows || !rows.length) return []
  const sensitiveKeys = ['password', 'deleted', 'createTime', 'updateTime', 'cleanErrors', 'cleanTime']
  const labelMap = {
    id: 'ID', orderNo: '订单号', platform: '平台', shopId: '店铺ID',
    currency: '币种', amount: '金额', fee: '手续费', settleAmount: '结算金额',
    orderTime: '订单时间', settleTime: '结算时间', reconcileStatus: '对账',
    username: '用户名', employeeNo: '工号', realName: '真实姓名',
    phone: '手机号', email: '邮箱',
    status: '状态', ruleName: '规则名称', ruleType: '类型', enabled: '启用',
    costType: '费用类型', period: '周期', payee: '收款方', costDate: '费用日期',
    rateDate: '汇率日期', fromCurrency: '源币种', toCurrency: '目标币种',
    rate: '汇率', applyAmount: '金额', source: '来源', batchNo: '批次号'
  }
  return Object.keys(rows[0])
    .filter(k => !sensitiveKeys.includes(k))
    .slice(0, 8) // 最多显示 8 列
    .map(k => ({ prop: k, label: labelMap[k] || k, width: k === 'id' ? 70 : 120 }))
}

// 搜索条件
const search = reactive(loadField('search', {
  username: '',
  timeRange: []
}))

// 分页与表格数据
const tableData = ref([])
const total = ref(0)
const loading = ref(false)
const page = reactive(loadField('page', { page: 1, size: 10 }))

// 详情弹窗
const detailVisible = ref(false)
const detailData = ref({})

// 选中行（批量删除）
const selection = ref([])

// 时间格式化
function formatTime(t) {
  if (!t) return '-'
  return String(t).replace('T', ' ').split('.')[0]
}

// 表格单元格值格式化
function formatCellValue(v) {
  if (v === null || v === undefined) return '-'
  const s = String(v)
  if (s.length > 10 && s.includes('T')) return s.replace('T', ' ').split('.')[0]
  return s
}

// 加载审计日志
async function loadData() {
  loading.value = true
  try {
    const params = {
      username: search.username,
      page: page.page,
      size: page.size
    }
    if (search.timeRange && search.timeRange.length === 2) {
      params.startTime = search.timeRange[0] + 'T00:00:00'
      params.endTime = search.timeRange[1] + 'T23:59:59'
    }
    const res = await pageAuditLog(params)
    tableData.value = res.data?.records || []
    total.value = res.data?.total || 0
  } catch (e) {
    // 错误已由请求拦截器统一处理
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  page.page = 1
  loadData()
}

function handleReset() {
  search.username = ''
  search.timeRange = []
  page.page = 1
  loadData()
}

// 查看详情
function handleDetail(row) {
  detailData.value = row
  detailVisible.value = true
}

// 选中行变化
function handleSelectionChange(rows) {
  selection.value = rows
}

// 批量删除
function handleBatchDelete() {
  if (!selection.value.length) {
    ElMessage.warning('请至少选择一条记录')
    return
  }
  const ids = selection.value.map(r => r.id)
  ElMessageBox.confirm(`确定删除选中的 ${ids.length} 条日志吗？`, '批量删除', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning'
  })
    .then(async () => {
      try {
        await batchDeleteAuditLog(ids)
        ElMessage.success('批量删除成功')
        loadData()
      } catch (e) {
        // 错误已由请求拦截器统一处理
      }
    })
    .catch(() => {})
}

function handleSizeChange(size) {
  page.size = size
  page.page = 1
  loadData()
}

// 撤销操作
function handleUndo(row) {
  const label = getUndoLabel(row.operation)
  ElMessageBox.confirm(
    `确定要${label}吗？\n\n操作描述：${row.operation}\n操作人：${row.username || '-'}\n操作时间：${formatTime(row.createTime)}\n\n撤销后将恢复操作前的数据状态，此操作不可重复执行。`,
    '撤销确认',
    { confirmButtonText: '确定撤销', cancelButtonText: '取消', type: 'warning' }
  )
    .then(async () => {
      try {
        const res = await undoAuditLog(row.id)
        ElMessage.success(res.data || '撤销成功')
        loadData()
      } catch (e) {
        // 错误已由请求拦截器统一处理
      }
    })
    .catch(() => {})
}

function handleCurrentChange(p) {
  page.page = p
  loadData()
}

// watch 搜索条件变化自动查询
watch(
  () => ({ ...search }),
  () => {
    page.page = 1
    loadData()
  },
  { deep: true }
)

// 页面状态持久化：切换页面后再切回时，自动恢复上次的搜索条件和页号
watch(search, v => saveField('search', { ...v }), { deep: true })
watch(page, v => saveField('page', { ...v }), { deep: true })

onMounted(() => {
  loadData()
})
</script>

<template>
  <div class="page-card">
    <!-- 搜索栏 + 操作按钮 -->
    <div class="flex-between mb-16">
      <el-form :inline="true" :model="search">
        <el-form-item label="用户名">
          <el-input
            v-model="search.username"
            placeholder="请输入用户名"
            clearable
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="操作时间">
          <el-date-picker
            v-model="search.timeRange"
            type="daterange"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            value-format="YYYY-MM-DD"
            style="width: 260px"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSearch">
            <el-icon><Search /></el-icon> 查询
          </el-button>
          <el-button @click="handleReset">
            <el-icon><Refresh /></el-icon> 重置
          </el-button>
        </el-form-item>
      </el-form>
      <el-button type="danger" :disabled="!selection.length" @click="handleBatchDelete">
        <el-icon><Delete /></el-icon> 批量删除
      </el-button>
    </div>

    <!-- 日志表格 -->
    <el-table
      :data="tableData"
      v-loading="loading"
      border
      stripe
      style="width: 100%"
      @selection-change="handleSelectionChange"
    >
      <el-table-column type="selection" width="50" />
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column label="用户名" width="160">
        <template #default="{ row }">
          <span>{{ row.username || '-' }}</span>
          <el-tag v-if="row.userId" size="small" type="success" style="margin-left: 4px">已登录</el-tag>
          <el-tag v-else size="small" type="warning" style="margin-left: 4px">未登录</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="operation" label="操作描述" min-width="220" show-overflow-tooltip />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'danger'">
            {{ row.status === 1 ? '成功' : '失败' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="撤销" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.undone === 1" type="info">已撤销</el-tag>
          <el-tag v-else type="primary">未撤销</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作时间" width="170">
        <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="handleDetail(row)">详情</el-button>
          <el-button
            v-if="getUndoLabel(row.operation)"
            link
            type="warning"
            :disabled="!canUndo(row)"
            @click="handleUndo(row)"
          >{{ getUndoLabel(row.operation) }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页 -->
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

    <!-- 详情弹窗 -->
    <el-dialog v-model="detailVisible" title="日志详情" width="600px">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="日志ID">{{ detailData.id }}</el-descriptions-item>
        <el-descriptions-item label="用户名">
          {{ detailData.username || '-' }}
          <el-tag v-if="detailData.userId" size="small" type="success" style="margin-left: 8px">已登录</el-tag>
          <el-tag v-else size="small" type="warning" style="margin-left: 8px">未登录</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="用户ID">{{ detailData.userId || '-' }}</el-descriptions-item>
        <el-descriptions-item label="操作描述">{{ detailData.operation }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="detailData.status === 1 ? 'success' : 'danger'">
            {{ detailData.status === 1 ? '成功' : '失败' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="撤销状态">
          <el-tag v-if="detailData.undone === 1" type="info">已撤销</el-tag>
          <el-tag v-else type="primary">未撤销</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="操作时间">{{ formatTime(detailData.createTime) }}</el-descriptions-item>
        <el-descriptions-item label="错误信息">
          <pre class="json-box">{{ detailData.errorMsg || '-' }}</pre>
        </el-descriptions-item>
      </el-descriptions>

      <!-- 操作详情：根据旧值快照解析展示 -->
      <div v-if="parseOldValue(detailData.oldValue)" class="op-detail-section">
        <div class="op-detail-title">{{ parseOldValue(detailData.oldValue).title }}</div>
        <!-- items 模式：键值对展示 -->
        <el-descriptions v-if="parseOldValue(detailData.oldValue).type === 'items'" :column="2" border size="small">
          <el-descriptions-item
            v-for="item in parseOldValue(detailData.oldValue).items"
            :key="item.label"
            :label="item.label"
          >{{ item.value }}</el-descriptions-item>
        </el-descriptions>
        <!-- table 模式：实体表格展示 -->
        <el-table
          v-if="parseOldValue(detailData.oldValue).type === 'table'"
          :data="parseOldValue(detailData.oldValue).rows"
          border
          stripe
          size="small"
          style="width: 100%; margin-top: 4px"
          :max-height="300"
        >
          <el-table-column
            v-for="col in getEntityColumns(parseOldValue(detailData.oldValue).rows)"
            :key="col.prop"
            :prop="col.prop"
            :label="col.label"
            :width="col.width"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              {{ formatCellValue(row[col.prop]) }}
            </template>
          </el-table-column>
        </el-table>
      </div>
      <!-- 原始旧值快照（折叠） -->
      <el-collapse class="raw-json-collapse">
        <el-collapse-item title="原始旧值快照（JSON）">
          <pre class="json-box">{{ detailData.oldValue || '无' }}</pre>
        </el-collapse-item>
      </el-collapse>
      <template #footer>
        <el-button
          v-if="getUndoLabel(detailData.operation)"
          type="warning"
          :disabled="!canUndo(detailData)"
          @click="handleUndo(detailData); detailVisible = false"
        >{{ getUndoLabel(detailData.operation) }}</el-button>
        <el-button type="primary" @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.json-box {
  background-color: #f5f7fa;
  padding: 8px;
  border-radius: 4px;
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 200px;
  overflow: auto;
  margin: 0;
}
.op-detail-section {
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid #ebeef5;
}
.op-detail-title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 8px;
  padding-left: 8px;
  border-left: 3px solid #409eff;
}
.raw-json-collapse {
  margin-top: 12px;
}
.raw-json-collapse :deep(.el-collapse-item__header) {
  font-size: 13px;
  color: #909399;
}
</style>
